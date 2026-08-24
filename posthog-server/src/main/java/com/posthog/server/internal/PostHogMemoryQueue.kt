package com.posthog.server.internal

import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import com.posthog.internal.PostHogApi
import com.posthog.internal.PostHogApiEndpoint
import com.posthog.internal.PostHogApiError
import com.posthog.internal.PostHogQueueInterface
import com.posthog.internal.executeSafely
import com.posthog.internal.isNetworkingError
import java.io.IOException
import java.util.Date
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.schedule
import kotlin.math.min

/**
 * Memory-only implementation of PostHogQueueInterface that stores events in memory without persistence
 * @property config the Config
 * @property api the API
 * @property endpoint the API endpoint to use
 * @property executor the Executor
 */
internal class PostHogMemoryQueue(
    private val config: PostHogConfig,
    private val api: PostHogApi,
    private val endpoint: PostHogApiEndpoint,
    private val executor: ExecutorService,
    private val retryDelaySeconds: Int = DEFAULT_RETRY_DELAY_SECONDS,
    private val maxRetryDelaySeconds: Int = DEFAULT_MAX_RETRY_DELAY_SECONDS,
    private val fatalFlushTimeoutMs: Long = FATAL_FLUSH_TIMEOUT_MS,
) : PostHogQueueInterface<PostHogEvent> {
    private val events: ArrayDeque<PostHogEvent> = ArrayDeque()
    private val eventsLock = Any()
    private val timerLock = Any()
    private var pausedUntil: Date? = null
    private var retryCount = 0

    @Volatile
    private var timer: Timer? = null

    @Volatile
    private var timerTask: TimerTask? = null

    private var isFlushing = AtomicBoolean(false)

    private val delay: Long get() = (config.flushIntervalSeconds * 1000).toLong()

    override fun add(record: PostHogEvent) {
        // Same fatal-record marker the core PostHogQueue keys on: a fatal $exception event is about
        // to take the process down with it, so it must not take the regular async path.
        if (record.isFatalExceptionEvent()) {
            addFatalBlocking(record)
            return
        }

        executor.executeSafely {
            enqueue(record)
            flushIfOverThreshold()
        }
    }

    private fun enqueue(record: PostHogEvent) {
        var removedEvent: PostHogEvent? = null

        synchronized(eventsLock) {
            if (events.size >= config.maxQueueSize) {
                removedEvent = events.removeFirstOrNull()
            }

            events.addLast(record)
        }

        if (removedEvent != null) {
            config.logger.log("Queue is full, the oldest event ${removedEvent?.event} was discarded.")
        }

        config.logger.log("Event: ${record.event} was added to the queue.")
    }

    override fun flush() {
        // only flushes if the queue has events
        if (!isAboveThreshold(1)) {
            return
        }

        flushIgnoringThreshold()
    }

    /**
     * Crash path for fatal `$exception` events: enqueue and drain in one ordered task on the queue
     * executor, blocking the calling (crashing) thread for at most [fatalFlushTimeoutMs].
     *
     * The regular [add] path submits the enqueue asynchronously, so a crashing thread that flushed
     * inline could read the deque before its own event landed, send nothing, and let the event die
     * with the JVM. Running enqueue + drain as one task on the single-threaded executor makes the
     * send happen-after the enqueue, and draining batch by batch (ignoring `flushAt`) until the
     * queue is empty keeps a backlog of `maxBatchSize` or more from stranding the fatal event,
     * which FIFO puts last.
     *
     * Delivery stays best-effort: the caller stops waiting at the timeout (the drain keeps going on
     * the executor thread for whatever process lifetime remains), and each HTTP attempt can fail or
     * be cut short by process exit.
     */
    private fun addFatalBlocking(record: PostHogEvent) {
        val done = CountDownLatch(1)
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(fatalFlushTimeoutMs)

        try {
            executor.execute {
                try {
                    enqueue(record)
                    drainUntilDeadline(deadlineNanos)
                } finally {
                    done.countDown()
                }
            }
        } catch (e: RejectedExecutionException) {
            // the executor is gone (client closed mid-crash); there is nothing to await
            config.logger.log("The fatal event flush could not be scheduled: $e.")
            return
        }

        awaitUninterruptibly(done, fatalFlushTimeoutMs)
    }

    /**
     * Sends batch after batch until the queue is empty, the [deadlineNanos] budget is spent, or a
     * pass makes no progress (a failed send requeues its batch, and a flush already in progress on
     * another thread makes the pass a no-op) — a crashing process gets one straight-line attempt
     * per batch, never a retry loop.
     */
    private fun drainUntilDeadline(deadlineNanos: Long) {
        while (System.nanoTime() < deadlineNanos) {
            val before = synchronized(eventsLock) { events.size }
            if (before == 0) {
                return
            }
            flushIgnoringThreshold()
            val after = synchronized(eventsLock) { events.size }
            if (after >= before) {
                return
            }
        }
    }

    /**
     * Waits out the whole [timeoutMs] even when the calling thread is already interrupted or gets
     * interrupted while waiting — a crash on a thread some shutdown just interrupted must still get
     * its flush attempt instead of returning on the spot with the budget unspent. The interrupt flag
     * is restored before returning so the caller's own handling still sees it.
     */
    private fun awaitUninterruptibly(
        latch: CountDownLatch,
        timeoutMs: Long,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var interrupted = false
        try {
            while (true) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) {
                    config.logger.log("Blocking flush timed out after $timeoutMs ms.")
                    return false
                }
                try {
                    if (latch.await(remaining, TimeUnit.NANOSECONDS)) {
                        return true
                    }
                } catch (e: InterruptedException) {
                    // await clears the interrupt status when it throws, so the next wait really waits.
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun flushIgnoringThreshold() {
        if (isFlushing.getAndSet(true)) {
            config.logger.log("Queue is flushing.")
            return
        }

        executeBatch()
    }

    override fun start() {
        executor.executeSafely {
            synchronized(timerLock) {
                if (timer == null) {
                    // Daemon, matching the core PostHogQueue's Timer(true) and the executor's
                    // daemon threads: a background flush timer must never keep a finished (or
                    // crashed) JVM alive until close() is called.
                    timer = Timer(true)
                    startTimer(delay)
                    config.logger.log("Queue timer started.")
                }
            }
        }
    }

    override fun stop() {
        executor.executeSafely {
            synchronized(timerLock) {
                timerTask?.cancel()
                timerTask = null
                timer?.cancel()
                timer = null
                config.logger.log("Queue timer stopped.")
            }
        }
    }

    override fun clear() {
        executor.executeSafely {
            synchronized(eventsLock) {
                val eventsRemoved = events.size
                events.clear()
                config.logger.log("$eventsRemoved events cleared from Queue.")
            }
        }
    }

    private fun flushIfOverThreshold() {
        if (isAboveThreshold(config.flushAt)) {
            flushBatch()
        }
    }

    private fun isAboveThreshold(flushAt: Int): Boolean {
        val size = synchronized(eventsLock) { events.size }
        if (size >= flushAt) {
            return true
        } else if (size > 0) {
            // only log if there are events in the queue
            config.logger.log("Cannot flush the Queue yet, below the threshold: $flushAt")
        }
        return false
    }

    private fun canFlushBatch(): Boolean {
        if (pausedUntil?.after(config.dateProvider.currentDate()) == true) {
            config.logger.log("Queue is paused until $pausedUntil")
            return false
        }

        if (config.networkStatus?.isConnected() == false) {
            config.logger.log("Device is offline.")
            return false
        }

        return true
    }

    private fun takeEvents(): List<PostHogEvent> {
        val eventsToProcess: MutableList<PostHogEvent> = mutableListOf()
        synchronized(eventsLock) {
            val maxToTake = min(config.maxBatchSize, events.size)
            repeat(maxToTake) {
                events.removeFirstOrNull()?.let { event ->
                    eventsToProcess.add(event)
                }
            }
        }
        return eventsToProcess
    }

    private fun flushBatch() {
        if (!canFlushBatch()) {
            config.logger.log("Cannot flush the queue.")
            return
        }

        if (isFlushing.getAndSet(true)) {
            config.logger.log("Queue is flushing.")
            return
        }

        executeBatch()
    }

    private fun executeBatch() {
        var retry = false
        try {
            batchEvents()
            retryCount = 0
        } catch (e: Throwable) {
            config.logger.log("Flushing failed: $e.")

            retry = true
            retryCount++
        } finally {
            calculateDelay(retry)

            isFlushing.set(false)
        }
    }

    @Throws(PostHogApiError::class, IOException::class)
    private fun batchEvents() {
        val eventsToProcess = takeEvents()

        if (eventsToProcess.isEmpty()) {
            return
        }

        try {
            config.logger.log("Flushing ${eventsToProcess.size} events.")
            when (endpoint) {
                PostHogApiEndpoint.BATCH -> api.batch(eventsToProcess)
                PostHogApiEndpoint.SNAPSHOT -> api.snapshot(eventsToProcess)
            }
            // Events successfully sent, no need to put them back
        } catch (e: PostHogApiError) {
            // Put events back at the front of the queue if an intermittent error occurs
            if (e.isNetworkingError() || e.statusCode >= 500) {
                synchronized(eventsLock) {
                    // Add events back to the front of the queue in reverse order
                    eventsToProcess.asReversed().forEach { event ->
                        events.addFirst(event)
                    }
                }
                config.logger.log("Flushing failed because of a network error, let's try again soon.")
            } else {
                // Don't put events back for non-network errors (they're likely bad data)
                config.logger.log("Flushing failed: $e")
            }
            throw e
        }
    }

    private fun startTimer(delay: Long) {
        synchronized(timerLock) {
            timerTask?.cancel()
            timerTask =
                timer?.schedule(delay) {
                    flushBatch()
                    startTimer(this@PostHogMemoryQueue.delay)
                }
        }
    }

    private fun calculateDelay(retry: Boolean) {
        if (retry) {
            val delayInSeconds = min(retryDelaySeconds * retryCount, maxRetryDelaySeconds)
            pausedUntil =
                config.dateProvider.currentDate().let {
                    Date(it.time + (delayInSeconds * 1000))
                }

            config.logger.log("Retrying in $delayInSeconds seconds.")
        } else {
            pausedUntil = null
        }
    }

    public companion object {
        private const val DEFAULT_RETRY_DELAY_SECONDS = 5
        private const val DEFAULT_MAX_RETRY_DELAY_SECONDS = 60

        // How long a crashing thread waits for the fatal enqueue + drain, mirroring the Rust SDK's
        // bounded panic-hook flush. Bounded so telemetry can never hang a dying process.
        internal const val FATAL_FLUSH_TIMEOUT_MS = 2_000L
    }
}

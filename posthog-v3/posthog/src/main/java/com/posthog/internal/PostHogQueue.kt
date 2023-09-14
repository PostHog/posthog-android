package com.posthog.internal

import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import java.util.Calendar
import java.util.Date
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.schedule

// TODO: move to disk cache instead of memory cache (using PostHogStorage)
internal class PostHogQueue(private val config: PostHogConfig, private val storage: PostHogStorage, private val api: PostHogApi) {

    private val deque: ArrayDeque<PostHogEvent> = ArrayDeque()
    private val dequeLock = Any()
    private val timerLock = Any()
    private var pausedUntil: Date? = null

    @Volatile
    private var timer: Timer? = null

    @Volatile
    private var timerTask: TimerTask? = null

    // https://github.com/square/tape/blob/master/tape/src/main/java/com/squareup/tape2/QueueFile.java
    private var isFlushing = AtomicBoolean(false)

    private val delay: Long get() = (config.flushIntervalSeconds * 1000).toLong()

    private val executor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("PostHogBatchThread"))

//    init {
    // TOOD: load deque from disk
//    }

    fun add(event: PostHogEvent) {
        if (deque.size >= config.maxQueueSize) {
            try {
                val first: PostHogEvent
                synchronized(dequeLock) {
                    first = deque.removeFirst()
                }
                config.logger?.log("Queue is full, the oldest event ${first.event} is dropped.")
            } catch (ignore: NoSuchElementException) {}
        }

        synchronized(dequeLock) {
            deque.add(event)
        }
        config.logger?.log("Queued event ${event.event}.")

        flushIfOverThreshold()
    }

    private fun flushIfOverThreshold() {
        if (deque.size >= config.flushAt) {
            flushBatch()
        }
    }

    private fun canFlushBatch(): Boolean {
        if (pausedUntil?.after(Date()) == true) {
            config.logger?.log("Queue is paused until $pausedUntil")
            return false
        }

        return true
    }

    private fun takeEvents(): List<PostHogEvent> {
        val events: List<PostHogEvent>
        synchronized(dequeLock) {
            events = deque.take(config.maxBatchSize)
        }
        return events
    }

    private fun flushBatch() {
        if (!canFlushBatch()) {
            config.logger?.log("Cannot flush the Queue.")
            return
        }

        if (isFlushing.getAndSet(true)) {
            config.logger?.log("Queue is flushing.")
            return
        }

        executor.execute {
            try {
                batchEvents()
            } catch (e: Throwable) {
                // TODO: retry?
                config.logger?.log("Flushing failed: $e")
            }

            pausedUntil = calculatePausedUntil()

            isFlushing.set(false)
        }
    }

    private fun batchEvents() {
        val events = takeEvents()

        api.batch(events)

        synchronized(dequeLock) {
            deque.removeAll(events)
        }
    }

    fun flush() {
        if (isFlushing.getAndSet(true)) {
            config.logger?.log("Queue is flushing.")
            return
        }

        executor.execute {
            try {
                while (deque.isNotEmpty()) {
                    batchEvents()
                }
            } catch (e: Throwable) {
                // TODO: retry?
                config.logger?.log("Flushing failed: $e")
            }

            pausedUntil = calculatePausedUntil()

            isFlushing.set(false)
        }
    }

    private fun calculatePausedUntil(): Date {
        val cal = Calendar.getInstance()
        cal.add(Calendar.SECOND, config.flushIntervalSeconds)
        return cal.time
    }

    fun start() {
        synchronized(timerLock) {
            stopTimer()
            val timer = Timer(true)
            val timerTask = timer.schedule(delay, delay) {
                // early check to avoid more checks when its already flushing
                if (isFlushing.get()) {
                    config.logger?.log("Queue is flushing.")
                    return@schedule
                }
                flushIfOverThreshold()
            }
            this.timerTask = timerTask
            this.timer = timer
        }
    }

    private fun stopTimer() {
        timerTask?.cancel()
        timer?.cancel()
    }

    fun stop() {
        synchronized(timerLock) {
            stopTimer()
        }
    }
}

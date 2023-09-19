package com.posthog.internal

import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import java.io.File
import java.util.Calendar
import java.util.Date
import java.util.Timer
import java.util.TimerTask
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.schedule
import kotlin.math.min

internal class PostHogQueue(private val config: PostHogConfig, private val storage: PostHogStorage, private val api: PostHogApi, private val serializer: PostHogSerializer) {

    private val deque: ArrayDeque<File> = ArrayDeque()
    private val dequeLock = Any()
    private val timerLock = Any()
    private var pausedUntil: Date? = null
    private var retryCount = 0
    private val retryDelaySeconds = 5
    private val maxRetryDelaySeconds = 30

    @Volatile
    private var timer: Timer? = null

    @Volatile
    private var timerTask: TimerTask? = null

    private var isFlushing = AtomicBoolean(false)

    private val delay: Long get() = (config.flushIntervalSeconds * 1000).toLong()

    private val executor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("PostHogQueueThread"))

    fun add(event: PostHogEvent) {
        if (deque.size >= config.maxQueueSize) {
            try {
                val first: File
                synchronized(dequeLock) {
                    first = deque.removeFirst()
                }
                config.logger.log("Queue is full, the oldest event ${first.name} is dropped.")
            } catch (ignore: NoSuchElementException) {}
        }

        val dir = File(config.storagePrefix!!, config.apiKey)
        val file = File(dir, "${UUID.randomUUID()}.event")
        synchronized(dequeLock) {
            deque.add(file)
        }
        serializer.serializeEvent(event, file.writer().buffered())
        config.logger.log("Queued event ${file.name}.")

        flushIfOverThreshold()
    }

    private fun flushIfOverThreshold() {
        if (deque.size >= config.flushAt) {
            flushBatch()
        }
    }

    private fun canFlushBatch(): Boolean {
        if (pausedUntil?.after(Date()) == true) {
            config.logger.log("Queue is paused until $pausedUntil")
            return false
        }

        return true
    }

    private fun takeEvents(): List<File> {
        val events: List<File>
        synchronized(dequeLock) {
            events = deque.take(config.maxBatchSize)
        }
        return events
    }

    private fun flushBatch() {
        if (!canFlushBatch()) {
            config.logger.log("Cannot flush the Queue.")
            return
        }

        if (isFlushing.getAndSet(true)) {
            config.logger.log("Queue is flushing.")
            return
        }

        executor.execute {
            var retry = false
            try {
                batchEvents()
                retryCount = 0
            } catch (e: Throwable) {
                config.logger.log("Flushing failed: $e")

                // TODO: when do we actually drop those events? maybe they are broken for good
                // and the SDK will be stuck at them
                retry = true
                retryCount++
            }

            calculateDelay(retry)

            isFlushing.set(false)
        }
    }

    private fun batchEvents() {
        val files = takeEvents()

        val events = mutableListOf<PostHogEvent>()
        for (file in files) {
            val event = serializer.deserializeEvent(file.reader().buffered())
            event?.let {
                events.add(it)
            }
        }

        api.batch(events)

        synchronized(dequeLock) {
            deque.removeAll(files)
        }
    }

    fun flush() {
        if (!canFlushBatch()) {
            config.logger.log("Cannot flush the Queue.")
            return
        }

        if (isFlushing.getAndSet(true)) {
            config.logger.log("Queue is flushing.")
            return
        }

        executor.execute {
            var retry = false
            try {
                while (deque.isNotEmpty()) {
                    batchEvents()
                }
                retryCount = 0
            } catch (e: Throwable) {
                config.logger.log("Flushing failed: $e")
                retry = true
                retryCount++
            }

            calculateDelay(retry)

            isFlushing.set(false)
        }
    }

    private fun calculateDelay(retry: Boolean) {
        val delay = if (retry) min(retryCount * retryDelaySeconds, maxRetryDelaySeconds) else config.flushIntervalSeconds
        pausedUntil = calculatePausedUntil(delay)
    }

    private fun calculatePausedUntil(seconds: Int): Date {
        val cal = Calendar.getInstance()
        cal.add(Calendar.SECOND, seconds)
        return cal.time
    }

    fun start() {
        executor.execute {
            config.storagePrefix?.let {
                val file = File(it, config.apiKey)

                if (!file.exists()) {
                    file.mkdirs()
                }

//                flushLegacyEvents(file)

//                synchronized(dequeLock) {
//                }
            }
        }

        synchronized(timerLock) {
            stopTimer()
            val timer = Timer(true)
            val timerTask = timer.schedule(delay, delay) {
                // early check to avoid more checks when its already flushing
                if (isFlushing.get()) {
                    config.logger.log("Queue is flushing.")
                    return@schedule
                }
                flushIfOverThreshold()
            }
            this.timerTask = timerTask
            this.timer = timer
        }
    }

//    private fun flushLegacyEvents(file: File) {
//        config.legacyStoragePrefix?.let {
//            val legacyDir = File(it)
//            val legacyFile = File(legacyDir, "${config.apiKey}.tmp")
//
//            if (legacyFile.exists()) {
//                val legacy = PostHogQueueFile.Builder(legacyFile)
//                    .forceLegacy(true)
//                    .build()
//                val iterator = legacy.iterator()
//                while (iterator.hasNext()) {
//                    val next = iterator.next()
//
//                    val nextFile = File(file, "${UUID.randomUUID()}.event")
//                    nextFile.writeBytes(next)
//
//                    iterator.remove()
//                }
//            }
//            legacyFile.delete()
//        }
//    }

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

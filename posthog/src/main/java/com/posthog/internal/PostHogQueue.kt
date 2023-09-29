package com.posthog.internal

import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import java.io.File
import java.io.IOException
import java.util.Calendar
import java.util.Date
import java.util.Timer
import java.util.TimerTask
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.schedule
import kotlin.math.min

internal class PostHogQueue(private val config: PostHogConfig, private val api: PostHogApi, private val serializer: PostHogSerializer) {

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

    private var dirCreated = false

    private val delay: Long get() = (config.flushIntervalSeconds * 1000).toLong()

    private val executor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("PostHogQueueThread"))

    fun add(event: PostHogEvent) {
        var removeFirst = false
        if (deque.size >= config.maxQueueSize) {
            removeFirst = true
        }

        executor.execute {
            if (removeFirst) {
                try {
                    val first: File
                    synchronized(dequeLock) {
                        first = deque.removeFirst()
                    }
                    first.deleteSafely(config)
                    config.logger.log("Queue is full, the oldest event ${first.name} is dropped.")
                } catch (ignore: NoSuchElementException) {}
            }

            config.storagePrefix?.let {
                val dir = File(it, config.apiKey)

                if (!dirCreated) {
                    dir.mkdirs()
                }

                val file = File(dir, "${UUID.randomUUID()}.event")
                synchronized(dequeLock) {
                    deque.add(file)
                }

                try {
                    val os = config.encryption?.encrypt(file.outputStream()) ?: file.outputStream()
                    serializer.serializeEvent(event, os.writer().buffered())
                    config.logger.log("Queued event ${file.name}.")

                    flushIfOverThreshold()
                } catch (e: Throwable) {
                    config.logger.log("Event ${event.event} failed to parse: $e.")
                }
            }
        }
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

    private fun takeFiles(): List<File> {
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
            if (!isConnected()) {
                return@execute
            }

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
    }

    private fun batchEvents() {
        val files = takeFiles()

        val events = mutableListOf<PostHogEvent>()
        for (file in files) {
            try {
                val inputStream = config.encryption?.decrypt(file.inputStream()) ?: file.inputStream()

                val event = serializer.deserializeEvent(inputStream.reader().buffered())
                event?.let {
                    events.add(it)
                }
            } catch (e: Throwable) {
                synchronized(dequeLock) {
                    deque.remove(file)
                }
                file.deleteSafely(config)
                config.logger.log("File: ${file.name} failed to parse: $e.")
            }
        }

        if (events.isNotEmpty()) {
            var deleteFiles = true
            try {
                api.batch(events)
            } catch (e: PostHogApiError) {
                if (e.statusCode < 400) {
                    deleteFiles = false
                }
                throw e
            } catch (e: IOException) {
                // no connection should try again
                if (e.isNetworkingError()) {
                    deleteFiles = false
                }
                throw e
            } finally {
                if (deleteFiles) {
                    synchronized(dequeLock) {
                        deque.removeAll(files)
                    }

                    files.forEach {
                        it.deleteSafely(config)
                    }
                }
            }
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
            if (!isConnected()) {
                return@execute
            }

            var retry = false
            try {
                while (deque.isNotEmpty()) {
                    batchEvents()
                }
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
    }

    private fun isConnected(): Boolean {
        if (config.networkStatus?.isConnected() == false) {
            config.logger.log("Network isn't connected.")
            return false
        }
        return true
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

    private fun stopTimer() {
        timerTask?.cancel()
        timer?.cancel()
    }

    fun stop() {
        synchronized(timerLock) {
            stopTimer()
        }
    }

    fun clear() {
        executor.execute {
            val tempFiles: List<File>
            synchronized(dequeLock) {
                tempFiles = deque.toList()
                deque.clear()
            }
            tempFiles.forEach {
                it.deleteSafely(config)
            }
        }
    }
}

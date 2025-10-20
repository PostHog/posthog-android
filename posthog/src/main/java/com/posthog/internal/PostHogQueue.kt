package com.posthog.internal

import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import com.posthog.PostHogVisibleForTesting
import com.posthog.vendor.uuid.TimeBasedEpochGenerator
import java.io.File
import java.io.IOException
import java.util.Date
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.schedule
import kotlin.math.max
import kotlin.math.min

/**
 * The class that manages the events Queue
 * @property config the Config
 * @property api the API
 * @property executor the Executor
 */
internal class PostHogQueue(
    private val config: PostHogConfig,
    private val api: PostHogApi,
    private val endpoint: PostHogApiEndpoint,
    private val storagePrefix: String?,
    private val executor: ExecutorService,
) : PostHogQueueInterface {
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

    private fun addEventSync(event: PostHogEvent): Boolean {
        storagePrefix?.let {
            val dir = File(it, config.apiKey)

            if (!dirCreated) {
                dir.mkdirs()
                dirCreated = true
            }

            val uuid = event.uuid ?: TimeBasedEpochGenerator.generate()
            val file = File(dir, "$uuid.event")
            synchronized(dequeLock) {
                deque.add(file)
            }

            try {
                val os = config.encryption?.encrypt(file.outputStream()) ?: file.outputStream()
                os.use { theOutputStream ->
                    config.serializer.serialize(event, theOutputStream.writer().buffered())
                }
                config.logger.log("Queued Event ${event.event}: ${file.name}.")

                return true
            } catch (e: Throwable) {
                config.logger.log("Event ${event.event}: ${file.name} failed to parse: $e.")

                // if for some reason the file failed to serialize, lets delete it
                file.deleteSafely(config)
            }

            return false
        }

        // if there's no storagePrefix, we assume it failed
        return true
    }

    private fun removeEventSync() {
        if (deque.size >= config.maxQueueSize) {
            try {
                val first: File
                synchronized(dequeLock) {
                    first = deque.removeFirst()
                }
                first.deleteSafely(config)
                config.logger.log("Queue is full, the oldest event ${first.name} is dropped.")
            } catch (ignored: NoSuchElementException) {
            }
        }
    }

    private fun flushEventSync(
        event: PostHogEvent,
        isFatal: Boolean = false,
    ) {
        removeEventSync()
        if (addEventSync(event)) {
            // this is best effort since we dont know if theres
            // enough time to flush events to the wire
            flushIfOverThreshold(isFatal)
        }
    }

    override fun add(event: PostHogEvent) {
        if (event.isFatalExceptionEvent()) {
            executor.submitSyncSafely {
                flushEventSync(event, true)
            }
        } else {
            executor.executeSafely {
                flushEventSync(event)
            }
        }
    }

    private fun flushIfOverThreshold(isFatal: Boolean) {
        if (isAboveThreshold(config.flushAt)) {
            flushBatch(isFatal)
        }
    }

    private fun isAboveThreshold(flushAt: Int): Boolean {
        if (deque.size >= flushAt) {
            return true
        } else if (deque.size > 0) {
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

        return true
    }

    private fun takeFiles(): List<File> {
        val events: List<File>
        synchronized(dequeLock) {
            events = deque.take(config.maxBatchSize)
        }
        return events
    }

    private fun flushBatch(isFatal: Boolean) {
        if (!isFatal && !canFlushBatch()) {
            config.logger.log("Cannot flush the Queue.")
            return
        }

        if (isFlushing.getAndSet(true)) {
            config.logger.log("Queue is flushing.")
            return
        }

        executeBatch()
    }

    private fun executeBatch() {
        if (!isConnected()) {
            isFlushing.set(false)
            return
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

    private fun deleteFileSafely(
        file: File,
        throwable: Throwable? = null,
    ) {
        synchronized(dequeLock) {
            deque.remove(file)
        }
        file.deleteSafely(config)
        config.logger.log("File: ${file.name} failed to parse: $throwable.")
    }

    @Throws(PostHogApiError::class, IOException::class)
    private fun batchEvents() {
        val files = takeFiles()

        val events = mutableListOf<PostHogEvent>()
        for (file in files) {
            try {
                val inputStream = config.encryption?.decrypt(file.inputStream()) ?: file.inputStream()
                inputStream.use {
                    val event = config.serializer.deserialize<PostHogEvent?>(it.reader().buffered())
                    event?.let { theEvent ->
                        events.add(theEvent)
                    } ?: run {
                        deleteFileSafely(file)
                    }
                }
            } catch (e: Throwable) {
                deleteFileSafely(file, e)
            }
        }

        var deleteFiles = true
        try {
            if (events.isNotEmpty()) {
                config.logger.log("Flushing ${events.size} events.")

                when (endpoint) {
                    PostHogApiEndpoint.BATCH -> api.batch(events)
                    PostHogApiEndpoint.SNAPSHOT -> api.snapshot(events)
                }

                config.logger.log("Flushed ${events.size} events successfully.")
            }
        } catch (e: PostHogApiError) {
            deleteFiles = deleteFilesIfAPIError(e, config)

            throw e
        } catch (e: IOException) {
            // no connection should try again
            if (e.isNetworkingError()) {
                deleteFiles = false
                config.logger.log("Flushing failed because of a network error, let's try again soon.")
            } else {
                config.logger.log("Flushing failed: $e")
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

    override fun flush() {
        // only flushes if the queue is above the threshold (not empty in this case)
        if (!isAboveThreshold(1)) {
            return
        }

        if (isFlushing.getAndSet(true)) {
            config.logger.log("Queue is flushing.")
            return
        }

        executor.executeSafely {
            if (!isConnected()) {
                isFlushing.set(false)
                return@executeSafely
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
        if (retry) {
            val delay = min(retryCount * retryDelaySeconds, maxRetryDelaySeconds)
            pausedUntil = config.dateProvider.addSecondsToCurrentDate(delay)
        }
    }

    override fun start() {
        synchronized(timerLock) {
            stopTimer()
            val timer = Timer(true)
            val timerTask =
                timer.schedule(delay, delay) {
                    // early check to avoid more checks when its already flushing
                    if (isFlushing.get()) {
                        config.logger.log("Queue is flushing.")
                        return@schedule
                    }
                    // if the timer passes, send pending events, no need to wait for the flushAt threshold
                    flush()
                }
            this.timerTask = timerTask
            this.timer = timer
        }
    }

    private fun stopTimer() {
        timerTask?.cancel()
        timer?.cancel()
    }

    override fun stop() {
        synchronized(timerLock) {
            stopTimer()
        }
    }

    override fun clear() {
        executor.executeSafely {
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

    val dequeList: List<File>
        @PostHogVisibleForTesting
        get() {
            val tempFiles: List<File>
            synchronized(dequeLock) {
                tempFiles = deque.toList()
            }
            return tempFiles
        }
}

private fun calcFloor(currentValue: Int): Int {
    return max(currentValue.floorDiv(2), 1)
}

internal fun deleteFilesIfAPIError(
    e: PostHogApiError,
    config: PostHogConfig,
): Boolean {
    if (e.statusCode < 400) {
        config.logger.log("Flushing failed with ${e.statusCode}, let's try again soon.")

        return false
    }
    // workaround due to png images exceed our max. limit in kafka
    if (e.statusCode == 413 && config.maxBatchSize > 1) {
        // try to reduce the batch size and flushAt until its 1
        // and if it still throws 413 in the next retry, delete the files since we cannot handle anyway
        config.maxBatchSize = calcFloor(config.maxBatchSize)
        config.flushAt = calcFloor(config.flushAt)

        config.logger.log("Flushing failed with ${e.statusCode}, let's try again with a smaller batch.")

        return false
    }
    return true
}

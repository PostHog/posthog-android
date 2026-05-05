package com.posthog.internal

import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import com.posthog.PostHogInternal
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
import kotlin.math.min
import kotlin.math.pow

private val RETRYABLE_STATUS_CODES = setOf(429, 500, 502, 503, 504)

/**
 * The class that manages the events Queue
 * @property config the Config
 * @property api the API
 * @property executor the Executor
 */
@PostHogInternal
public class PostHogQueue(
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
    private val batchLimits = initialBatchLimits(config)
    private val initialRetryDelaySeconds = 1
    private val maxRetryDelaySeconds = 30

    @Volatile
    private var timer: Timer? = null

    @Volatile
    private var timerTask: TimerTask? = null

    private var isFlushing = AtomicBoolean(false)

    @Volatile
    private var cachedEventsLoaded = false

    private var dirCreated = false

    private val delay: Long get() = (config.flushIntervalSeconds * 1000).toLong()

    public val queueDirectory: File?
        get() = storagePrefix?.let { File(it, config.apiKey) }

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

    /**
     * Ensures cached events from disk are loaded into the deque exactly once.
     * Must be called on the executor thread (single-threaded executor, no lock needed).
     */
    private fun ensureCachedEventsLoaded() {
        if (!cachedEventsLoaded) {
            try {
                loadCachedEvents()
            } catch (e: Throwable) {
                config.logger.log("Failed to load cached events: $e.")
            } finally {
                cachedEventsLoaded = true
            }
        }
    }

    private fun flushEventSync(
        event: PostHogEvent,
        isFatal: Boolean = false,
    ) {
        ensureCachedEventsLoaded()
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
        if (isAboveThreshold(batchLimits.flushAt)) {
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
            events = deque.take(batchLimits.cap)
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

    private fun executeWithRetry(block: () -> Unit) {
        var retry = false
        var retryAfterSeconds: Int? = null
        try {
            block()
            retryCount = 0
            pausedUntil = null
        } catch (e: Throwable) {
            config.logger.log("Flushing failed: $e.")

            retryCount++

            if (retryCount > config.maxRetries) {
                config.logger.log("Max retries (${config.maxRetries}) exceeded, dropping events.")
                retryCount = 0
                pausedUntil = null
                dropAllEvents()
            } else {
                retry = true

                if (e is PostHogApiError) {
                    retryAfterSeconds = e.retryAfterSeconds
                }
            }
        } finally {
            calculateDelay(retry, retryAfterSeconds)

            isFlushing.set(false)
        }
    }

    private fun executeBatch() {
        if (!isConnected()) {
            isFlushing.set(false)
            return
        }

        executeWithRetry {
            batchEvents()
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
            deleteFiles = deleteFilesIfAPIError(e, batchLimits, events.size, config.logger)

            // only re-throw if retriable (files kept), so executeWithRetry
            // can track retryCount and apply backoff
            if (!deleteFiles) {
                throw e
            }
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
        if (isFlushing.getAndSet(true)) {
            config.logger.log("Queue is flushing.")
            return
        }

        executor.executeSafely {
            // load any cached events from disk before checking the threshold
            ensureCachedEventsLoaded()

            if (!isConnected()) {
                isFlushing.set(false)
                return@executeSafely
            }

            // respect Retry-After / backoff pause
            if (!canFlushBatch()) {
                isFlushing.set(false)
                return@executeSafely
            }

            // only flushes if the queue is above the threshold (not empty in this case)
            if (!isAboveThreshold(1)) {
                isFlushing.set(false)
                return@executeSafely
            }

            executeWithRetry {
                while (deque.isNotEmpty()) {
                    batchEvents()
                }
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

    private fun dropAllEvents() {
        val tempFiles: List<File>
        synchronized(dequeLock) {
            tempFiles = deque.toList()
            deque.clear()
        }
        tempFiles.forEach {
            it.deleteSafely(config)
        }
    }

    private fun calculateDelay(
        retry: Boolean,
        retryAfterSeconds: Int? = null,
    ) {
        if (retry) {
            val delay =
                if (retryAfterSeconds != null && retryAfterSeconds > 0) {
                    retryAfterSeconds
                } else {
                    min(initialRetryDelaySeconds * 2.0.pow((retryCount - 1).toDouble()).toInt(), maxRetryDelaySeconds)
                }
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

        config.networkStatus?.register {
            config.logger.log("Network is available, flushing queued events.")
            flush()
        }
    }

    /**
     * Loads cached event files from disk into the deque so they are sent in order
     * with any new events added after SDK start.
     */
    private fun loadCachedEvents() {
        val files = loadQueueFilesFromDisk()
        if (files.isEmpty()) return

        synchronized(dequeLock) {
            // prepend cached files before any events already in the deque
            // so that older events are sent first
            val existingFiles = deque.toList()
            deque.clear()
            deque.addAll(files)
            deque.addAll(existingFiles)
        }
        config.logger.log("Loaded ${files.size} cached events from disk for $endpoint.")
    }

    private fun loadQueueFilesFromDisk(): List<File> {
        val dir = queueDirectory ?: return emptyList()

        if (!dir.existsSafely(config)) {
            return emptyList()
        }

        val files = (dir.listFiles() ?: emptyArray()).toMutableList()
        if (files.isEmpty()) {
            return emptyList()
        }

        // sort by last modified date ascending so events are sent in order
        files.sortBy { file -> file.lastModified() }
        return files
    }

    private fun reloadFromDiskSync() {
        val files = loadQueueFilesFromDisk()
        synchronized(dequeLock) {
            deque.clear()
            deque.addAll(files)
        }
        cachedEventsLoaded = true
    }

    /**
     * Rebuilds the in-memory deque from disk, sorted by file last-modified time.
     */
    @PostHogInternal
    public fun reloadFromDisk() {
        executor.submitSyncSafely {
            reloadFromDiskSync()
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

        config.networkStatus?.unregister()
    }

    override fun clear() {
        executor.executeSafely {
            dropAllEvents()
        }
    }

    internal val dequeList: List<File>
        @PostHogVisibleForTesting
        get() {
            val tempFiles: List<File>
            synchronized(dequeLock) {
                tempFiles = deque.toList()
            }
            return tempFiles
        }

    val currentBatchCapForTesting: Int
        @PostHogVisibleForTesting
        get() = batchLimits.cap

    val currentFlushAtForTesting: Int
        @PostHogVisibleForTesting
        get() = batchLimits.flushAt
}

internal class BatchLimits(
    var cap: Int,
    var flushAt: Int,
) {
    fun halve(actualBatchSize: Int) {
        cap =
            minOf(cap, actualBatchSize)
                .div(2)
                .coerceAtLeast(1)

        // keep flushAt <= cap so we don't pile up events larger than a single batch
        flushAt =
            (flushAt / 2)
                .coerceAtMost(cap)
                .coerceAtLeast(1)
    }
}

internal fun initialBatchLimits(config: PostHogConfig) =
    BatchLimits(
        cap = config.maxBatchSize.coerceAtLeast(1),
        flushAt = config.flushAt.coerceAtLeast(1),
    )

internal fun deleteFilesIfAPIError(
    e: PostHogApiError,
    batchLimits: BatchLimits,
    actualBatchSize: Int,
    logger: PostHogLogger,
): Boolean {
    if (e.statusCode < 400) {
        logger.log("Flushing failed with ${e.statusCode}, let's try again soon.")

        return false
    }
    // workaround due to png images exceed our max. limit in kafka
    if (e.statusCode == 413 && batchLimits.cap > 1) {
        // try to reduce the batch size and flushAt until its 1
        // and if it still throws 413 in the next retry, delete the files since we cannot handle anyway
        batchLimits.halve(actualBatchSize)

        logger.log("Flushing failed with ${e.statusCode}, let's try again with a smaller batch.")

        return false
    }
    // Transient server errors and rate limiting, retry
    if (e.statusCode in RETRYABLE_STATUS_CODES) {
        logger.log("Flushing failed with ${e.statusCode}, let's try again soon.")

        return false
    }
    return true
}

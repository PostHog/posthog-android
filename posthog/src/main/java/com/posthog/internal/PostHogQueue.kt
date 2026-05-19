package com.posthog.internal

import com.posthog.PostHogConfig
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

/**
 * Generic file-backed queue. One implementation, multiple instances —
 * events, replay snapshots, and future endpoints (logs) each get their
 * own instance with their own [EndpointSpec] and dispatch executor.
 */
@PostHogInternal
public class PostHogQueue<Record>(
    private val config: PostHogConfig,
    private val spec: EndpointSpec<Record>,
    private val executor: ExecutorService,
) : PostHogQueueInterface<Record> {
    private val deque: ArrayDeque<File> = ArrayDeque()
    private val dequeLock = Any()
    private val timerLock = Any()
    private var pausedUntil: Date? = null
    private var retryCount = 0
    private val batchLimits =
        BatchLimits(cap = spec.initialCap(config).coerceAtLeast(1), flushAt = spec.initialFlushAt(config).coerceAtLeast(1))
    private val initialRetryDelaySeconds = 1
    private val maxRetryDelaySeconds = 30

    @Volatile
    private var timer: Timer? = null

    @Volatile
    private var timerTask: TimerTask? = null

    private var isFlushing = AtomicBoolean(false)

    @Volatile
    private var cachedRecordsLoaded = false

    private var dirCreated = false

    private val delay: Long get() = (spec.flushIntervalSeconds(config) * 1000).toLong()

    public val queueDirectory: File?
        get() = spec.storagePrefix?.let { File(it, config.apiKey) }

    private fun addRecordSync(record: Record): Boolean {
        spec.storagePrefix?.let {
            val dir = File(it, config.apiKey)

            if (!dirCreated) {
                dir.mkdirs()
                dirCreated = true
            }

            val uuid = spec.recordUuid(record) ?: TimeBasedEpochGenerator.generate()
            val file = File(dir, "$uuid.event")
            synchronized(dequeLock) {
                deque.add(file)
            }

            try {
                val os = config.encryption?.encrypt(file.outputStream()) ?: file.outputStream()
                os.use { theOutputStream ->
                    spec.encode(record, theOutputStream)
                }
                config.logger.log("Queued ${spec.describe(record)}: ${file.name}.")

                return true
            } catch (e: Throwable) {
                config.logger.log("${spec.describe(record)}: ${file.name} failed to parse: $e.")

                // if for some reason the file failed to serialize, lets delete it
                file.deleteSafely(config)
            }

            return false
        }

        // if there's no storagePrefix, we assume it failed
        return true
    }

    private fun removeRecordSync() {
        if (deque.size >= spec.maxQueueSize(config)) {
            try {
                val first: File
                synchronized(dequeLock) {
                    first = deque.removeFirst()
                }
                first.deleteSafely(config)
                config.logger.log("Queue is full, the oldest ${spec.name} ${first.name} is dropped.")
            } catch (ignored: NoSuchElementException) {
            }
        }
    }

    /**
     * Ensures cached records from disk are loaded into the deque exactly once.
     * Must be called on the executor thread (single-threaded executor, no lock needed).
     */
    private fun ensureCachedRecordsLoaded() {
        if (!cachedRecordsLoaded) {
            try {
                loadCachedRecords()
            } catch (e: Throwable) {
                config.logger.log("Failed to load cached ${spec.name}: $e.")
            } finally {
                cachedRecordsLoaded = true
            }
        }
    }

    private fun flushRecordSync(
        record: Record,
        isFatal: Boolean = false,
    ) {
        ensureCachedRecordsLoaded()
        removeRecordSync()
        if (addRecordSync(record)) {
            // this is best effort since we dont know if theres
            // enough time to flush records to the wire
            flushIfOverThreshold(isFatal)
        }
    }

    override fun add(record: Record) {
        if (spec.isFatalRecord(record)) {
            executor.submitSyncSafely {
                flushRecordSync(record, true)
            }
        } else {
            executor.executeSafely {
                flushRecordSync(record)
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
            // only log if there are records in the queue
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
                config.logger.log("Max retries (${config.maxRetries}) exceeded, dropping ${spec.name}.")
                retryCount = 0
                pausedUntil = null
                dropAllRecords()
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
            batchRecords()
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
    private fun batchRecords() {
        val files = takeFiles()

        val records = mutableListOf<Record>()
        for (file in files) {
            try {
                val inputStream = config.encryption?.decrypt(file.inputStream()) ?: file.inputStream()
                inputStream.use {
                    val record = spec.decode(it)
                    record?.let { theRecord ->
                        records.add(theRecord)
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
            if (records.isNotEmpty()) {
                config.logger.log("Flushing ${records.size} ${spec.name}.")

                spec.send(records)

                config.logger.log("Flushed ${records.size} ${spec.name} successfully.")
            }
        } catch (e: PostHogApiError) {
            deleteFiles = deleteFilesIfAPIError(e, batchLimits, records.size, config.logger, spec.isRetriableStatusCode)

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
            // load any cached records from disk before checking the threshold
            ensureCachedRecordsLoaded()

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
                    batchRecords()
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

    private fun dropAllRecords() {
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
                    // if the timer passes, send pending records, no need to wait for the flushAt threshold
                    flush()
                }
            this.timerTask = timerTask
            this.timer = timer
        }

        config.networkStatus?.register {
            config.logger.log("Network is available, flushing queued ${spec.name}.")
            flush()
        }
    }

    /**
     * Loads cached record files from disk into the deque so they are sent in order
     * with any new records added after SDK start.
     */
    private fun loadCachedRecords() {
        val files = loadQueueFilesFromDisk()
        if (files.isEmpty()) return

        synchronized(dequeLock) {
            // prepend cached files before any records already in the deque
            // so that older records are sent first
            val existingFiles = deque.toList()
            deque.clear()
            deque.addAll(files)
            deque.addAll(existingFiles)
        }
        config.logger.log("Loaded ${files.size} cached ${spec.name} from disk.")
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

        // sort by last modified date ascending so records are sent in order
        files.sortBy { file -> file.lastModified() }
        return files
    }

    private fun reloadFromDiskSync() {
        val files = loadQueueFilesFromDisk()
        synchronized(dequeLock) {
            deque.clear()
            deque.addAll(files)
        }
        cachedRecordsLoaded = true
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
            dropAllRecords()
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

    internal val currentBatchCapForTesting: Int
        @PostHogVisibleForTesting
        get() = batchLimits.cap

    internal val currentFlushAtForTesting: Int
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

        // keep flushAt <= cap so we don't pile up records larger than a single batch
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
    isRetriableStatusCode: (Int) -> Boolean = ::isEventsRetriableStatusCode,
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
    if (isRetriableStatusCode(e.statusCode)) {
        logger.log("Flushing failed with ${e.statusCode}, let's try again soon.")

        return false
    }
    return true
}

package com.posthog.internal

import com.google.gson.annotations.SerializedName
import com.posthog.PostHogConfig
import java.io.File
import java.io.IOException
import java.util.Timer
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.schedule
import kotlin.math.min
import kotlin.math.pow

private const val PENDING_FILE_NAME = "push_subscription.pending"
private val RETRYABLE_STATUS_CODES = setOf(429, 500, 502, 503, 504)

/**
 * Persists the latest push subscription registration to disk and retries it on
 * transient failures. The wire call is a single POST, so events here are
 * latest-wins: each new register() overwrites any pending record.
 *
 * Recovery across process restarts is driven by [retryPending], which the SDK
 * invokes once after setup completes.
 */
internal class PostHogPushSubscriptionManager(
    private val config: PostHogConfig,
    private val api: PostHogApi,
    private val executor: ExecutorService,
) {
    private val timerLock = Any()
    private val isSending = AtomicBoolean(false)

    @Volatile private var retryCount = 0

    @Volatile private var timer: Timer? = null

    private val initialRetryDelaySeconds = 1
    private val maxRetryDelaySeconds = 30

    fun register(
        distinctId: String,
        deviceToken: String,
        appId: String,
        platform: String,
    ) {
        val record = PendingRecord(distinctId, deviceToken, appId, platform)
        executor.executeSafely {
            val file = pendingFile()
            if (file != null) {
                writeRecord(file, record)
            }
            retryCount = 0
            cancelTimer()
            attempt(record, file)
        }
    }

    fun retryPending() {
        val file = pendingFile() ?: return
        executor.executeSafely {
            if (!file.existsSafely(config)) {
                return@executeSafely
            }
            val record =
                readRecord(file) ?: run {
                    file.deleteSafely(config)
                    return@executeSafely
                }
            retryCount = 0
            cancelTimer()
            attempt(record, file)
        }
    }

    private fun attempt(
        record: PendingRecord,
        file: File?,
    ) {
        if (config.networkStatus?.isConnected() == false) {
            config.logger.log("Push subscription deferred: no network.")
            return
        }

        if (!isSending.compareAndSet(false, true)) {
            return
        }

        try {
            api.pushSubscription(
                distinctId = record.distinctId,
                deviceToken = record.deviceToken,
                platform = record.platform,
                appId = record.appId,
            )
            config.logger.log("Push notification token registered successfully.")
            retryCount = 0
            // Latest-wins: only delete if the on-disk record hasn't been replaced
            // by a concurrent register() call.
            if (file != null && readRecord(file) == record) {
                file.deleteSafely(config)
            }
        } catch (e: Throwable) {
            handleFailure(e, record, file)
        } finally {
            isSending.set(false)
        }
    }

    private fun handleFailure(
        e: Throwable,
        record: PendingRecord,
        file: File?,
    ) {
        if (!isRetryable(e)) {
            config.logger.log("Push subscription failed with non-retryable error: $e.")
            file?.deleteSafely(config)
            retryCount = 0
            return
        }

        retryCount++
        if (retryCount > config.maxRetries) {
            config.logger.log(
                "Push subscription retries exhausted after $retryCount attempts; " +
                    "will retry on next SDK startup.",
            )
            retryCount = 0
            return
        }

        val retryAfter = (e as? PostHogApiError)?.retryAfterSeconds
        val delay =
            if (retryAfter != null && retryAfter > 0) {
                retryAfter
            } else {
                min(
                    initialRetryDelaySeconds * 2.0.pow((retryCount - 1).toDouble()).toInt(),
                    maxRetryDelaySeconds,
                )
            }
        config.logger.log("Push subscription failed: $e. Retrying in ${delay}s (attempt $retryCount).")
        scheduleRetry(delay, record, file)
    }

    private fun scheduleRetry(
        delaySeconds: Int,
        record: PendingRecord,
        file: File?,
    ) {
        synchronized(timerLock) {
            cancelTimer()
            val t = Timer(true)
            t.schedule(delaySeconds * 1000L) {
                executor.executeSafely {
                    // Re-read from disk in case the record was replaced while we waited.
                    val current = file?.let { readRecord(it) } ?: record
                    attempt(current, file)
                }
            }
            timer = t
        }
    }

    private fun cancelTimer() {
        synchronized(timerLock) {
            timer?.cancel()
            timer = null
        }
    }

    fun close() {
        cancelTimer()
        retryCount = 0
    }

    private fun isRetryable(e: Throwable): Boolean {
        return when (e) {
            is PostHogApiError -> e.statusCode < 400 || e.statusCode in RETRYABLE_STATUS_CODES
            is IOException -> true
            else -> false
        }
    }

    private fun pendingFile(): File? {
        val prefix = config.storagePrefix ?: return null
        val dir = File(prefix, config.apiKey)
        try {
            dir.mkdirs()
        } catch (e: Throwable) {
            config.logger.log("Failed to create push subscription dir: $e.")
            return null
        }
        return File(dir, PENDING_FILE_NAME)
    }

    private fun writeRecord(
        file: File,
        record: PendingRecord,
    ) {
        try {
            val os = config.encryption?.encrypt(file.outputStream()) ?: file.outputStream()
            os.use { theOutputStream ->
                config.serializer.serialize(record, theOutputStream.writer().buffered())
            }
        } catch (e: Throwable) {
            config.logger.log("Failed to persist push subscription: $e.")
        }
    }

    private fun readRecord(file: File): PendingRecord? {
        return try {
            val input = config.encryption?.decrypt(file.inputStream()) ?: file.inputStream()
            input.use {
                config.serializer.deserialize<PendingRecord?>(it.reader().buffered())
            }
        } catch (e: Throwable) {
            config.logger.log("Failed to read pending push subscription: $e.")
            null
        }
    }

    internal data class PendingRecord(
        @SerializedName("distinct_id")
        val distinctId: String,
        @SerializedName("device_token")
        val deviceToken: String,
        @SerializedName("app_id")
        val appId: String,
        val platform: String,
    )
}

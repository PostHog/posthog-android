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
private const val INITIAL_RETRY_DELAY_SECONDS = 5
private const val MAX_RETRY_DELAY_SECONDS = 30

/**
 * Persists the latest push subscription registration and retries it on transient failures.
 *
 * A single latest-wins record `{deviceToken, appId, platform}` is stored before the first
 * attempt; every new [register] overwrites it and resets the retry counter. The distinct id
 * is read at send time, never persisted with the record — only the id a successful send was
 * delivered for is remembered ([PendingRecord.deliveredForDistinctId]) so [resendIfDistinctIdChanged]
 * can re-register the token whenever the user identifies as someone new.
 *
 * The record survives success (kept, not deleted) and non-retryable failures alike, so
 * [retryPending] can pick it back up on the next process start. In-session resume: offline
 * deferrals re-poll on a timer, and [retryPending] is also invoked from `flush()` (which the
 * Android SDK calls on app background), so an undelivered record doesn't wait for a relaunch.
 */
internal class PostHogPushSubscriptionManager(
    private val config: PostHogConfig,
    private val api: PostHogApi,
    private val executor: ExecutorService,
    private val distinctIdProvider: () -> String,
) {
    private val timerLock = Any()
    private val isSending = AtomicBoolean(false)

    // Authoritative record within a process; disk is only the cross-launch backing store,
    // read once (lazily) to hydrate this field. Also the sole store when storagePrefix is null.
    @Volatile private var pendingRecord: PendingRecord? = null

    @Volatile private var hydratedFromDisk = false

    @Volatile private var retryCount = 0

    @Volatile private var timer: Timer? = null

    // Not-before gate for server-driven backoff (Retry-After / 429 / 5xx): while now < nextAttemptAtMs
    // the scheduled timer owns the next send, so resume paths (flush/identify) must not cancel it and
    // re-hit the endpoint early. Zero when no backoff window is active.
    @Volatile private var nextAttemptAtMs: Long = 0L

    @Volatile private var closed = false

    private val pendingFile: File? by lazy {
        val prefix = config.storagePrefix ?: return@lazy null
        // Must stay out of <storagePrefix>/<apiKey>: PostHogQueue scans that whole directory as
        // cached event files and would send the record as an empty event, then delete it.
        File(File(File(prefix, "push"), config.apiKey), PENDING_FILE_NAME)
    }

    // Test seam: computed backoff seconds are multiplied by this to get the scheduled delay in
    // millis. Production keeps the real 1000; tests shrink it so retries fire near-instantly.
    internal var retryDelayMillisPerSecond: Long = 1_000L

    fun register(
        deviceToken: String,
        appId: String,
        platform: String,
    ) {
        executor.executeSafely {
            performRegister(deviceToken, appId, platform)
        }
    }

    // Executor-thread body of [register]. Kept separate so [handleReset] can chain a DELETE and a
    // re-register in a single executor task (ordered, and drained by one flush in tests).
    private fun performRegister(
        deviceToken: String,
        appId: String,
        platform: String,
    ) {
        val existing = currentRecord()
        if (existing != null &&
            existing.deviceToken == deviceToken &&
            existing.appId == appId &&
            existing.platform == platform &&
            existing.deliveredForDistinctId != null &&
            existing.deliveredForDistinctId == distinctIdProvider()
        ) {
            // Same token already delivered for this user; don't re-POST it on every cold start.
            return
        }
        val record = PendingRecord(deviceToken, appId, platform)
        pendingRecord = record
        hydratedFromDisk = true
        pendingFile?.let { writeRecord(it, record) }
        retryCount = 0
        nextAttemptAtMs = 0L
        cancelTimer()
        attempt(record)
    }

    fun retryPending() {
        executor.executeSafely {
            val record = currentRecord() ?: return@executeSafely
            if (record.deliveredForDistinctId != null && record.deliveredForDistinctId == distinctIdProvider()) {
                return@executeSafely
            }
            if (isWithinBackoffWindow()) {
                // A Retry-After/backoff retry is already scheduled; let it fire instead of re-hitting now.
                return@executeSafely
            }
            retryCount = 0
            cancelTimer()
            attempt(record)
        }
    }

    fun resendIfDistinctIdChanged() {
        executor.executeSafely {
            val record = currentRecord() ?: return@executeSafely
            val currentDistinctId = distinctIdProvider()
            if (currentDistinctId.isBlank() || record.deliveredForDistinctId == currentDistinctId) {
                return@executeSafely
            }
            if (isWithinBackoffWindow()) {
                // Honor the active Retry-After/backoff window; the scheduled timer reads the current
                // distinctId at send time, so the id change is still picked up when it fires.
                return@executeSafely
            }
            retryCount = 0
            cancelTimer()
            attempt(record)
        }
    }

    /**
     * Best-effort unregister: a single DELETE for [distinctId]. Unlike [register] there is no
     * pending record, timer, or backoff — a failure is logged and dropped (the backend also unsets
     * a dead token on the next send, and the durable path is the re-register POST).
     */
    fun unregister(
        distinctId: String,
        deviceToken: String,
        appId: String,
        platform: String,
    ) {
        executor.executeSafely {
            performUnregister(distinctId, deviceToken, appId, platform)
        }
    }

    // Executor-thread body of [unregister].
    private fun performUnregister(
        distinctId: String,
        deviceToken: String,
        appId: String,
        platform: String,
    ) {
        if (closed || config.optOut) {
            return
        }
        if (distinctId.isBlank() || deviceToken.isBlank() || appId.isBlank()) {
            config.logger.log("Push unregister skipped: missing distinctId, token, or appId.")
            return
        }
        try {
            api.pushUnsubscription(
                distinctId = distinctId,
                deviceToken = deviceToken,
                platform = platform,
                appId = appId,
            )
            config.logger.log("Push notification token unregistered successfully.")
        } catch (e: Throwable) {
            config.logger.log("Push unregister failed: $e. Ignoring (best-effort).")
        }
    }

    /**
     * reset()/logout: unregister the stored token for the old identity, then re-register it under
     * the new anonymous id ([performRegister] reads the current id at send time). No-op when nothing
     * is stored. Both run in one executor task, keeping the DELETE ordered before the re-register POST.
     */
    fun handleReset(oldDistinctId: String) {
        executor.executeSafely {
            val record = currentRecord() ?: return@executeSafely
            // Only unregister the OLD identity when it actually differs from the new one. When they
            // match (e.g. reuseAnonymousId on an anonymous user, where reset() keeps the same id) a
            // DELETE would unset the very id we re-register under — and performRegister's dedup guard
            // would then skip the re-POST, leaving the device unregistered.
            if (oldDistinctId != distinctIdProvider()) {
                performUnregister(oldDistinctId, record.deviceToken, record.appId, record.platform)
            }
            performRegister(record.deviceToken, record.appId, record.platform)
        }
    }

    /**
     * Public-API unregister: DELETE for the current distinct id, then forget the local record so a
     * later launch won't re-send it.
     */
    fun unregisterCurrent() {
        executor.executeSafely {
            val record = currentRecord()
            if (record == null) {
                config.logger.log("Push unregister skipped: no registered token.")
                return@executeSafely
            }
            performUnregister(distinctIdProvider(), record.deviceToken, record.appId, record.platform)
            clearRecord()
        }
    }

    private fun clearRecord() {
        pendingRecord = null
        hydratedFromDisk = true
        cancelTimer()
        pendingFile?.deleteSafely(config)
    }

    fun close() {
        closed = true
        cancelTimer()
        retryCount = 0
    }

    /** Opt-out: stop the retry/offline-poll timer now. The guard in [attempt] blocks any actual send. */
    fun onOptOut() {
        cancelTimer()
    }

    private fun isWithinBackoffWindow(): Boolean = System.currentTimeMillis() < nextAttemptAtMs

    private fun attempt(record: PendingRecord) {
        if (closed || config.optOut) {
            // Opt-out and shutdown both stop every send. Guarding at this single choke point covers
            // all callers: register, startup/flush retryPending, identify resend, and the retry timer.
            cancelTimer()
            return
        }
        if (config.networkStatus?.isConnected() == false) {
            config.logger.log("Push subscription deferred: no network.")
            // Deferral burns no retry attempt; poll again so registration resumes
            // within the session once connectivity returns.
            scheduleRetry(MAX_RETRY_DELAY_SECONDS, record)
            return
        }

        val distinctId = distinctIdProvider()
        if (distinctId.isBlank()) {
            config.logger.log("Push subscription deferred: distinctId is blank.")
            return
        }

        if (!isSending.compareAndSet(false, true)) {
            return
        }

        try {
            api.pushSubscription(
                distinctId = distinctId,
                deviceToken = record.deviceToken,
                platform = record.platform,
                appId = record.appId,
            )
            config.logger.log("Push notification token registered successfully.")
            retryCount = 0
            nextAttemptAtMs = 0L
            // Keep the record with the delivered marker so a later identify() can re-register.
            val delivered = record.copy(deliveredForDistinctId = distinctId)
            pendingRecord = delivered
            pendingFile?.let { writeRecord(it, delivered) }
        } catch (e: Throwable) {
            handleFailure(e, record)
        } finally {
            isSending.set(false)
        }
    }

    private fun handleFailure(
        e: Throwable,
        record: PendingRecord,
    ) {
        if (!isRetryable(e)) {
            // 400/401 etc.: stop retrying this session but keep the record for one retry next launch.
            config.logger.log("Push subscription failed with non-retryable error: $e.")
            retryCount = 0
            nextAttemptAtMs = 0L
            return
        }

        retryCount++
        if (retryCount > config.maxRetries) {
            config.logger.log(
                "Push subscription retries exhausted after $retryCount attempts; " +
                    "will retry on next SDK startup.",
            )
            retryCount = 0
            nextAttemptAtMs = 0L
            return
        }

        val delay = nextBackoffSeconds(retryCount, (e as? PostHogApiError)?.retryAfterSeconds)
        // Server-driven backoff: gate resume paths so flush()/identify() don't cancel this window
        // and immediately re-hit the endpoint, ignoring the server's Retry-After.
        nextAttemptAtMs = System.currentTimeMillis() + delay * retryDelayMillisPerSecond
        config.logger.log("Push subscription failed: $e. Retrying in ${delay}s (attempt $retryCount).")
        scheduleRetry(delay, record)
    }

    internal fun nextBackoffSeconds(
        attempt: Int,
        retryAfterSeconds: Int?,
    ): Int {
        if (retryAfterSeconds != null && retryAfterSeconds > 0) {
            return retryAfterSeconds
        }
        val exponential = INITIAL_RETRY_DELAY_SECONDS * 2.0.pow((attempt - 1).toDouble()).toInt()
        return min(exponential, MAX_RETRY_DELAY_SECONDS)
    }

    private fun scheduleRetry(
        delaySeconds: Int,
        record: PendingRecord,
    ) {
        synchronized(timerLock) {
            cancelTimer()
            val t = Timer(true)
            t.schedule(delaySeconds * retryDelayMillisPerSecond) {
                executor.executeSafely {
                    // A newer register() may have replaced the record while we waited.
                    attempt(currentRecord() ?: record)
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

    private fun isRetryable(e: Throwable): Boolean {
        return when (e) {
            is PostHogApiError -> e.statusCode == 429 || e.statusCode in 500..599
            is IOException -> true
            else -> false
        }
    }

    private fun currentRecord(): PendingRecord? {
        if (pendingRecord == null && !hydratedFromDisk) {
            hydratedFromDisk = true
            pendingFile?.takeIf { it.existsSafely(config) }?.let { file ->
                pendingRecord =
                    readRecord(file) ?: run {
                        file.deleteSafely(config)
                        null
                    }
            }
        }
        return pendingRecord
    }

    private fun writeRecord(
        file: File,
        record: PendingRecord,
    ) {
        try {
            file.parentFile?.mkdirs()
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
        @SerializedName("device_token")
        val deviceToken: String,
        @SerializedName("app_id")
        val appId: String,
        val platform: String,
        @SerializedName("delivered_for_distinct_id")
        val deliveredForDistinctId: String? = null,
    )
}

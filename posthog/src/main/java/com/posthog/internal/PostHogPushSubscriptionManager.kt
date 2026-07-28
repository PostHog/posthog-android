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

    // Set after a non-retryable failure or once retries are exhausted: no more attempts this session,
    // but the record is kept for one retry on the next launch. In-memory only (not persisted), so a
    // fresh process starts clear — this is what stops flush()-driven [retryPending] from re-POSTing a
    // doomed request on every app background. Cleared by a new [register] or an identity-change resend.
    @Volatile private var halted = false

    @Volatile private var closed = false

    // In-memory only: a short-lived credential must never land on disk; a fresh process re-mints
    // via config.pushIdentityProvider. Reused across in-session backoff retries for the same
    // (distinctId, appId); any other pair is a miss and re-mints.
    @Volatile private var cachedIdentityToken: CachedIdentityToken? = null

    // One fresh-token retry per send-cycle after a 401 (single refresh, no loop). Cleared on
    // success, a new register, and an identity-change resend; a fresh process starts clear.
    @Volatile private var didAuthRetry = false

    // A register/resend/retry that arrived while a send was in flight (isSending claimed across the
    // async mint) sets this instead of no-oping; [performSend] replays one fresh attempt on release
    // so the latest token isn't stranded behind the finished send. Executor-thread only.
    @Volatile private var pendingResend = false

    private val pendingFile: File? by lazy {
        val prefix = config.storagePrefix ?: return@lazy null
        // Must stay out of <storagePrefix>/<apiKey>: PostHogQueue scans that whole directory as
        // cached event files and would send the record as an empty event, then delete it.
        File(File(File(prefix, "push"), config.apiKey), PENDING_FILE_NAME)
    }

    // Test seam: computed backoff seconds are multiplied by this to get the scheduled delay in
    // millis. Production keeps the real 1000; tests shrink it so retries fire near-instantly.
    internal var retryDelayMillisPerSecond: Long = 1_000L

    // Watchdog window for pushIdentityProvider: if the host never calls completion within this, fall
    // back to a token-less send so a misbehaving provider can't wedge sending for the whole process.
    // Fixed 10s heuristic; a slow legitimate mint on a bad network is cut off and retried
    // token-less (401 re-mints). Tune here (and keep parity with iOS) if that proves too tight.
    internal var identityTokenMintTimeoutMillis: Long = 10_000L

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
        halted = false
        didAuthRetry = false
        cancelTimer()
        attempt()
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
            attempt()
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
            halted = false
            didAuthRetry = false
            cancelTimer()
            attempt()
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

    // Executor-thread body of [unregister]. [onComplete] runs after the DELETE has been sent (or
    // skipped) so callers can chain work that must not race the DELETE on the wire — see [handleReset].
    private fun performUnregister(
        distinctId: String,
        deviceToken: String,
        appId: String,
        platform: String,
        onComplete: (() -> Unit)? = null,
    ) {
        if (closed || config.optOut) {
            onComplete?.invoke()
            return
        }
        if (distinctId.isBlank() || deviceToken.isBlank() || appId.isBlank()) {
            config.logger.log("Push unregister skipped: missing distinctId, token, or appId.")
            onComplete?.invoke()
            return
        }
        // Best-effort stays single-shot: the token is resolved once (old id on the reset path) and
        // a 401 is not refreshed — the durable path is the re-register POST.
        resolveIdentityToken(distinctId, appId) { identityToken ->
            if (closed || config.optOut) {
                onComplete?.invoke()
                return@resolveIdentityToken
            }
            try {
                api.pushUnsubscription(
                    distinctId = distinctId,
                    deviceToken = deviceToken,
                    platform = platform,
                    appId = appId,
                    identityToken = identityToken,
                )
                config.logger.log("Push notification token unregistered successfully.")
            } catch (e: Throwable) {
                config.logger.log("Push unregister failed: $e. Ignoring (best-effort).")
            } finally {
                onComplete?.invoke()
            }
        }
    }

    /**
     * reset()/logout: unregister the stored token for the old identity, then re-register it under
     * the new anonymous id ([performRegister] reads the current id at send time). No-op when nothing
     * is stored. The re-register is chained on the DELETE's completion so the DELETE reaches the wire
     * before the re-register POST, even though identity-token minting makes both legs asynchronous.
     */
    fun handleReset(oldDistinctId: String) {
        executor.executeSafely {
            val record = currentRecord() ?: return@executeSafely
            // Only unregister the OLD identity when it actually differs from the new one. When they
            // match (e.g. reuseAnonymousId on an anonymous user, where reset() keeps the same id) a
            // DELETE would unset the very id we re-register under — and performRegister's dedup guard
            // would then skip the re-POST, leaving the device unregistered.
            if (oldDistinctId != distinctIdProvider()) {
                performUnregister(oldDistinctId, record.deviceToken, record.appId, record.platform) {
                    performRegister(record.deviceToken, record.appId, record.platform)
                }
            } else {
                performRegister(record.deviceToken, record.appId, record.platform)
            }
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
        cachedIdentityToken = null
    }

    /** Opt-out: stop the retry/offline-poll timer now. The guard in [attempt] blocks any actual send. */
    fun onOptOut() {
        cancelTimer()
        // Order both clears on the executor with the mint-completion cache write so an opt-out
        // mid-mint can't leave a stale token cached (the residual race resolveIdentityToken notes).
        // didAuthRetry is cleared too: a 401 before opt-out would otherwise strand the flag, and the
        // retryPending() resume path after opt-in doesn't clear it, so the next 401 goes terminal
        // with no refresh.
        executor.executeSafely {
            cachedIdentityToken = null
            didAuthRetry = false
        }
    }

    private fun isWithinBackoffWindow(): Boolean = System.currentTimeMillis() < nextAttemptAtMs

    private fun attempt() {
        if (closed || config.optOut) {
            // Opt-out and shutdown both stop every send. Guarding at this single choke point covers
            // all callers: register, startup/flush retryPending, identify resend, and the retry timer.
            cancelTimer()
            return
        }
        if (halted) {
            // Session halt set in handleFailure; this choke point makes flush()-driven retryPending() a no-op.
            return
        }
        // Read the record here, not from the caller: a retry timer may fire after unregisterCurrent()
        // cleared it (cancelTimer can't un-queue an already-fired callback), so a null means "don't send".
        val record = currentRecord() ?: return
        if (config.networkStatus?.isConnected() == false) {
            config.logger.log("Push subscription deferred: no network.")
            // Deferral burns no retry attempt; poll again so registration resumes
            // within the session once connectivity returns.
            scheduleRetry(MAX_RETRY_DELAY_SECONDS)
            return
        }

        val distinctId = distinctIdProvider()
        if (distinctId.isBlank()) {
            config.logger.log("Push subscription deferred: distinctId is blank.")
            return
        }

        if (!isSending.compareAndSet(false, true)) {
            // A send is already in flight (isSending is held across the async mint). Fold this request
            // in: [performSend] replays one fresh attempt on release so this token isn't dropped.
            pendingResend = true
            return
        }

        // isSending stays claimed across an async token mint so resume paths can't double-send;
        // [performSend] releases it on every path.
        resolveIdentityToken(distinctId, record.appId) { identityToken ->
            performSend(record, distinctId, identityToken)
        }
    }

    // Runs on the executor (inline from [attempt], or re-entered from the provider completion).
    // [distinctId] is the id the identity token was resolved for, so body and token always match.
    private fun performSend(
        record: PendingRecord,
        distinctId: String,
        identityToken: String?,
    ) {
        try {
            if (closed || config.optOut) {
                return
            }
            // The record can be cleared or replaced during the async mint (unregisterCurrent, or a
            // newer register). Re-read it and bail if it no longer matches, so a late mint can't
            // resurrect a just-DELETEd subscription or POST a token the newer record superseded.
            val current = currentRecord()
            if (current == null || current.deviceToken != record.deviceToken || current.appId != record.appId) {
                config.logger.log("Push subscription send skipped: record changed during identity token mint.")
                return
            }
            api.pushSubscription(
                distinctId = distinctId,
                deviceToken = record.deviceToken,
                platform = record.platform,
                appId = record.appId,
                identityToken = identityToken,
            )
            config.logger.log("Push notification token registered successfully.")
            retryCount = 0
            nextAttemptAtMs = 0L
            didAuthRetry = false
            // Keep the record with the delivered marker so a later identify() can re-register.
            val delivered = record.copy(deliveredForDistinctId = distinctId)
            pendingRecord = delivered
            pendingFile?.let { writeRecord(it, delivered) }
        } catch (e: Throwable) {
            handleFailure(e)
        } finally {
            isSending.set(false)
            servicePendingResend()
        }
    }

    // A register/resend/retry that arrived mid-send set [pendingResend] instead of sending. Replay one
    // fresh attempt now that isSending is released, so the latest token isn't stranded behind this
    // send's backoff or halt state. Executor-thread only (called from [performSend]).
    private fun servicePendingResend() {
        if (!pendingResend) {
            return
        }
        pendingResend = false
        if (closed || config.optOut || currentRecord() == null) {
            return
        }
        retryCount = 0
        nextAttemptAtMs = 0L
        halted = false
        didAuthRetry = false
        cancelTimer()
        attempt()
    }

    private fun handleFailure(e: Throwable) {
        if ((e as? PostHogApiError)?.statusCode == 401) {
            val provider = config.pushIdentityProvider
            if (provider != null && !didAuthRetry) {
                // One fresh-token retry, then terminal. Re-queued (not inline) so the failing
                // send's isSending release in [performSend] happens before the retry claims it.
                didAuthRetry = true
                cachedIdentityToken = null
                config.logger.log("Push subscription rejected (401): refreshing identity token and retrying once.")
                executor.executeSafely { attempt() }
                return
            }
            config.logger.log(
                "Push subscription rejected (401). " +
                    if (provider == null) {
                        "Identity verification may be required — configure pushIdentityProvider. " +
                            "Keeping record for next launch."
                    } else {
                        "Identity token refresh did not help. Keeping record for next launch."
                    },
            )
            haltForSession()
            return
        }
        if (!isRetryable(e)) {
            // 400 etc.: stop retrying this session but keep the record for one retry next launch.
            config.logger.log("Push subscription failed with non-retryable error: $e.")
            haltForSession()
            return
        }

        retryCount++
        if (retryCount > config.maxRetries) {
            config.logger.log(
                "Push subscription retries exhausted after $retryCount attempts; " +
                    "will retry on next SDK startup.",
            )
            haltForSession()
            return
        }

        val delay = nextBackoffSeconds(retryCount, (e as? PostHogApiError)?.retryAfterSeconds)
        // Server-driven backoff: gate resume paths so flush()/identify() don't cancel this window
        // and immediately re-hit the endpoint, ignoring the server's Retry-After.
        nextAttemptAtMs = System.currentTimeMillis() + delay * retryDelayMillisPerSecond
        config.logger.log("Push subscription failed: $e. Retrying in ${delay}s (attempt $retryCount).")
        scheduleRetry(delay)
    }

    // Stop retrying for this process; the persisted record is kept so the next launch (fresh
    // instance, halted cleared) retries once. Callers log their own branch-specific reason first.
    private fun haltForSession() {
        retryCount = 0
        nextAttemptAtMs = 0L
        halted = true
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

    private fun scheduleRetry(delaySeconds: Int) {
        synchronized(timerLock) {
            cancelTimer()
            val t = Timer(true)
            t.schedule(delaySeconds * retryDelayMillisPerSecond) {
                executor.executeSafely { attempt() }
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

    // Resolves the identity token for [distinctId]/[appId], preferring a cached exact match. The
    // provider's completion may arrive from any thread and only the first call counts; no provider,
    // a null completion, or a throw all fall back to token-less — the pre-identity behavior.
    private fun resolveIdentityToken(
        distinctId: String,
        appId: String,
        onResolved: (String?) -> Unit,
    ) {
        // Provider is checked before the cache: clearing pushIdentityProvider mid-session means "stop
        // attaching tokens now", so a stale cached credential must not outlive it (matches iOS).
        val provider = config.pushIdentityProvider
        if (provider == null) {
            config.logger.log("No identity token attached to push request (no pushIdentityProvider).")
            onResolved(null)
            return
        }
        val cached = cachedIdentityToken
        if (cached != null && cached.distinctId == distinctId && cached.appId == appId) {
            config.logger.log("Attaching cached identity token to push request.")
            onResolved(cached.token)
            return
        }
        val completed = AtomicBoolean(false)
        // A provider that never calls its completion would hold isSending for the whole process and
        // wedge every later send. Bound the wait: if the mint doesn't land in time, fall back to a
        // token-less send. A late real completion is a no-op via `completed`.
        val watchdog = Timer(true)
        watchdog.schedule(identityTokenMintTimeoutMillis) {
            if (completed.compareAndSet(false, true)) {
                config.logger.log(
                    "pushIdentityProvider did not complete within ${identityTokenMintTimeoutMillis}ms; sending without identity token.",
                )
                executor.executeSafely { onResolved(null) }
            }
            watchdog.cancel()
        }
        try {
            provider(distinctId, appId) { token ->
                if (completed.compareAndSet(false, true)) {
                    watchdog.cancel()
                    executor.executeSafely {
                        if (token != null) {
                            // A mint can complete after opt-out cleared the cache; caching it would
                            // resurrect a stale credential on a later opt-in. The 401 refresh covers
                            // the residual race window.
                            if (!closed && !config.optOut) {
                                cachedIdentityToken = CachedIdentityToken(token, distinctId, appId)
                            }
                            config.logger.log("Attaching freshly minted identity token to push request.")
                        } else {
                            config.logger.log("No identity token attached to push request (provider completed null).")
                        }
                        onResolved(token)
                    }
                }
            }
        } catch (e: Throwable) {
            watchdog.cancel()
            config.logger.log("pushIdentityProvider threw: $e. Sending without identity token.")
            if (completed.compareAndSet(false, true)) {
                onResolved(null)
            }
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

    private data class CachedIdentityToken(
        val token: String,
        val distinctId: String,
        val appId: String,
    )
}

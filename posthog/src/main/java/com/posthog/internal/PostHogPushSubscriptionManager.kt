package com.posthog.internal

import com.google.gson.annotations.SerializedName
import com.posthog.PostHogConfig
import java.io.File
import java.util.Timer
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.schedule
import kotlin.math.min
import kotlin.math.pow

private const val PENDING_FILE_NAME = "push_subscription.pending"
private const val PENDING_UNREGISTER_FILE_NAME = "push_subscription.unregister.pending"
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
 * [retryPending] can pick it back up on the next process start. In-session resume: [retryPending]
 * is invoked from `flush()` (which the Android SDK calls on app background), so an undelivered
 * record doesn't wait for a relaunch. An offline deferral schedules no timer — recovery is driven
 * by flush()/identify()/relaunch, matching iOS's passive model.
 *
 * Unregister (logout/reset) is durable the same way: a [PendingUnregister] intent is persisted
 * before the DELETE and only cleared on a 2xx or a terminal 4xx, so an offline or failed logout
 * is retried on flush()/next launch instead of leaving the device associated with the logged-out
 * user. Like registration it carries no backoff machine of its own — [retryPending] replays it.
 */
internal class PostHogPushSubscriptionManager(
    private val config: PostHogConfig,
    private val api: PostHogApi,
    private val executor: ExecutorService,
    private val distinctIdProvider: () -> String,
) {
    private val isSending = AtomicBoolean(false)

    // Authoritative record within a process; disk is only the cross-launch backing store,
    // read once (lazily) to hydrate this field. Also the sole store when storagePrefix is null.
    @Volatile private var pendingRecord: PendingRecord? = null

    @Volatile private var hydratedFromDisk = false

    // Single-slot, last-write-wins: only one identity's unregister can be pending at a time.
    @Volatile private var pendingUnregister: PendingUnregister? = null

    @Volatile private var hydratedUnregisterFromDisk = false

    @Volatile private var retryCount = 0

    // Not-before gate for server-driven backoff (Retry-After / 429 / 5xx): while now < nextAttemptAtMs
    // resume paths (flush/identify) must not re-hit the endpoint early. No timer is scheduled; the
    // next attempt happens when flush()/identify()/relaunch calls in after the window elapses.
    // Zero when no backoff window is active.
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

    private val pendingUnregisterFile: File? by lazy {
        val prefix = config.storagePrefix ?: return@lazy null
        File(File(File(prefix, "push"), config.apiKey), PENDING_UNREGISTER_FILE_NAME)
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

    // Executor-thread body of [register], shared with [handleReset]'s re-register leg.
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
        pendingFile?.let { writePending(it, record, "Failed to persist push subscription") }
        retryCount = 0
        nextAttemptAtMs = 0L
        halted = false
        didAuthRetry = false
        attempt()
    }

    fun retryPending() {
        executor.executeSafely {
            // Drain any pending unregister first (independent of the send record, usually absent after
            // a logout). If a same-identity registration is queued (logged out of A offline, then back
            // into A), drop the DELETE — completing after the POST it would kill the subscription just delivered.
            currentPendingUnregister()?.let { pending ->
                if (currentRecord() != null && pending.distinctId == distinctIdProvider()) {
                    clearPendingUnregister(pending)
                } else {
                    performUnregister(pending)
                }
            }

            val record = currentRecord() ?: return@executeSafely
            if (record.deliveredForDistinctId != null && record.deliveredForDistinctId == distinctIdProvider()) {
                return@executeSafely
            }
            if (isWithinBackoffWindow()) {
                // Honor the active Retry-After/backoff window; a later trigger retries once it elapses.
                return@executeSafely
            }
            // retryCount deliberately not reset: the exponential ladder persists across triggers, so
            // repeated failures still exhaust maxRetries and halt for the session.
            attempt()
        }
    }

    fun resendIfDistinctIdChanged() {
        executor.executeSafely {
            val record = currentRecord() ?: return@executeSafely
            val currentDistinctId = distinctIdProvider()
            // Only a record already delivered to a DIFFERENT id is fresh state; an undelivered
            // record keeps its backoff/halt and is driven by retryPending() instead.
            val delivered = record.deliveredForDistinctId
            if (currentDistinctId.isBlank() || delivered == null || delivered == currentDistinctId) {
                return@executeSafely
            }
            // An identity change is fresh state: clear any backoff window and retry immediately.
            retryCount = 0
            nextAttemptAtMs = 0L
            halted = false
            didAuthRetry = false
            attempt()
        }
    }

    /**
     * Unregister: `DELETE` for [distinctId]. The intent is persisted first, so an offline or failed
     * attempt is retried on flush()/next launch (see [PendingUnregister]) rather than dropped —
     * otherwise a logout while offline, or with an expired identity token, would leave the device
     * associated with the logged-out user on the backend. Cleared on a 2xx or a terminal 4xx.
     */
    fun unregister(
        distinctId: String,
        deviceToken: String,
        appId: String,
        platform: String,
    ) {
        executor.executeSafely {
            if (distinctId.isBlank() || deviceToken.isBlank() || appId.isBlank()) {
                config.logger.log("Push unregister skipped: missing distinctId, token, or appId.")
                return@executeSafely
            }
            val pending = PendingUnregister(distinctId, deviceToken, appId, platform)
            writePendingUnregister(pending)
            performUnregister(pending)
        }
    }

    // Executor-thread body of [unregister]. On a 401 with a provider, re-mints once and retries;
    // [isRetry] caps that to one re-mint.
    private fun performUnregister(
        pending: PendingUnregister,
        isRetry: Boolean = false,
    ) {
        if (closed || config.optOut) {
            return
        }
        if (pending.distinctId.isBlank() || pending.deviceToken.isBlank() || pending.appId.isBlank()) {
            config.logger.log("Push unregister skipped: missing distinctId, token, or appId.")
            return
        }
        if (config.networkStatus?.isConnected() == false) {
            config.logger.log("Push unregister deferred: no network. Will retry on flush/next launch.")
            return
        }
        resolveIdentityToken(pending.distinctId, pending.appId) { identityToken ->
            if (closed || config.optOut) {
                return@resolveIdentityToken
            }
            try {
                api.pushUnsubscription(
                    distinctId = pending.distinctId,
                    deviceToken = pending.deviceToken,
                    platform = pending.platform,
                    appId = pending.appId,
                    identityToken = identityToken,
                )
                clearPendingUnregister(pending)
                config.logger.log("Push notification token unregistered successfully.")
            } catch (e: Throwable) {
                handleUnregisterFailure(e, pending, isRetry)
            }
        }
    }

    private fun handleUnregisterFailure(
        e: Throwable,
        pending: PendingUnregister,
        isRetry: Boolean,
    ) {
        val statusCode = (e as? PostHogApiError)?.statusCode
        // 401: identity verification failed. Re-mint once and retry, mirroring the send-path 401 refresh.
        if (statusCode == 401 && config.pushIdentityProvider != null && !isRetry) {
            cachedIdentityToken = null
            config.logger.log("Push unregister rejected (401): refreshing identity token and retrying once.")
            executor.executeSafely { performUnregister(pending, isRetry = true) }
            return
        }
        // Retryable (transport error, 429, 5xx): keep the intent for flush/next launch.
        if (isRetryable(e)) {
            config.logger.log("Push unregister failed: $e. Will retry on flush/next launch.")
            return
        }
        // Terminal (post-retry 401, 404 already gone, other 4xx): drop the intent, best-effort ceiling.
        clearPendingUnregister(pending)
        config.logger.log("Push unregister failed (status ${statusCode ?: "none"}): $e. Dropping (best-effort).")
    }

    /**
     * reset()/logout: unregister the stored token for the old identity, then re-register it under
     * the new anonymous id ([performRegister] reads the current id at send time). No-op when nothing
     * is stored. The two legs are independent — the backend keys subscriptions per (person, app_id),
     * so the old-id DELETE and new-id POST can't affect each other in any order (matches iOS).
     */
    fun handleReset(oldDistinctId: String) {
        executor.executeSafely {
            val record = currentRecord() ?: return@executeSafely
            // Only unregister the OLD identity when it actually differs from the new one. When they
            // match (e.g. reuseAnonymousId on an anonymous user, where reset() keeps the same id) a
            // DELETE would unset the very id we re-register under — and performRegister's dedup guard
            // would then skip the re-POST, leaving the device unregistered.
            if (oldDistinctId != distinctIdProvider()) {
                val pending = PendingUnregister(oldDistinctId, record.deviceToken, record.appId, record.platform)
                writePendingUnregister(pending)
                performUnregister(pending)
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
            val pending = PendingUnregister(distinctIdProvider(), record.deviceToken, record.appId, record.platform)
            writePendingUnregister(pending)
            clearRecord()
            performUnregister(pending)
        }
    }

    private fun clearRecord() {
        pendingRecord = null
        hydratedFromDisk = true
        pendingFile?.deleteSafely(config)
    }

    fun close() {
        closed = true
        retryCount = 0
        cachedIdentityToken = null
    }

    /** Opt-out: the guard in [attempt] blocks any actual send. */
    fun onOptOut() {
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
            // all callers: register, startup/flush retryPending, and identify resend.
            return
        }
        if (halted) {
            // Session halt set in handleFailure; this choke point makes flush()-driven retryPending() a no-op.
            return
        }
        // Read the record here, not from the caller: an already-queued executor task can run after
        // unregisterCurrent() cleared it, so a null means "don't send".
        val record = currentRecord() ?: return
        if (config.networkStatus?.isConnected() == false) {
            config.logger.log("Push subscription deferred: no network.")
            // Deferral burns no retry attempt and schedules no timer; recovery is driven by
            // flush()/identify()/relaunch, matching iOS's passive model.
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
            pendingFile?.let { writePending(it, delivered, "Failed to persist push subscription") }
            // A fresh registration delivered to this identity supersedes any queued logout-DELETE for
            // it (log out of A, then back into A): otherwise the next retryPending() drain would
            // unregister the subscription we just re-registered.
            clearPendingUnregister(PendingUnregister(distinctId, record.deviceToken, record.appId, record.platform))
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
        // Server-driven backoff: gate resume paths so flush() doesn't immediately re-hit the
        // endpoint, ignoring the server's Retry-After. No timer — the next attempt is driven by
        // flush()/identify()/relaunch once the window elapses.
        nextAttemptAtMs = System.currentTimeMillis() + delay * retryDelayMillisPerSecond
        config.logger.log(
            "Push subscription failed: $e. Will retry on flush/identify/next launch after ${delay}s (attempt $retryCount).",
        )
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
            // Anything without an HTTP status (transport failures, unexpected throwables) is
            // retryable — unknown means keep trying.
            else -> true
        }
    }

    private fun currentRecord(): PendingRecord? {
        if (pendingRecord == null && !hydratedFromDisk) {
            hydratedFromDisk = true
            pendingFile?.takeIf { it.existsSafely(config) }?.let { file ->
                pendingRecord =
                    readPending<PendingRecord>(file, "Failed to read pending push subscription") ?: run {
                        file.deleteSafely(config)
                        null
                    }
            }
        }
        return pendingRecord
    }

    private fun currentPendingUnregister(): PendingUnregister? {
        if (pendingUnregister == null && !hydratedUnregisterFromDisk) {
            hydratedUnregisterFromDisk = true
            pendingUnregisterFile?.takeIf { it.existsSafely(config) }?.let { file ->
                pendingUnregister =
                    readPending<PendingUnregister>(file, "Failed to read pending push unregister") ?: run {
                        file.deleteSafely(config)
                        null
                    }
            }
        }
        return pendingUnregister
    }

    private fun writePendingUnregister(pending: PendingUnregister) {
        pendingUnregister = pending
        hydratedUnregisterFromDisk = true
        pendingUnregisterFile?.let { writePending(it, pending, "Failed to persist push unregister") }
    }

    // Clears the intent only if it's still the one that just resolved — a newer unregister (a second
    // logout while this DELETE was in flight) may have overwritten the slot, and its intent must not
    // be dropped by this stale completion.
    private fun clearPendingUnregister(matching: PendingUnregister) {
        if (currentPendingUnregister() != matching) {
            return
        }
        pendingUnregister = null
        pendingUnregisterFile?.deleteSafely(config)
    }

    private inline fun <reified T> writePending(
        file: File,
        value: T,
        failureLog: String,
    ) {
        try {
            file.parentFile?.mkdirs()
            val os = config.encryption?.encrypt(file.outputStream()) ?: file.outputStream()
            os.use { theOutputStream ->
                config.serializer.serialize(value, theOutputStream.writer().buffered())
            }
        } catch (e: Throwable) {
            config.logger.log("$failureLog: $e.")
        }
    }

    private inline fun <reified T> readPending(
        file: File,
        failureLog: String,
    ): T? {
        return try {
            val input = config.encryption?.decrypt(file.inputStream()) ?: file.inputStream()
            input.use {
                config.serializer.deserialize<T?>(it.reader().buffered())
            }
        } catch (e: Throwable) {
            config.logger.log("$failureLog: $e.")
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

    internal data class PendingUnregister(
        @SerializedName("distinct_id")
        val distinctId: String,
        @SerializedName("device_token")
        val deviceToken: String,
        @SerializedName("app_id")
        val appId: String,
        val platform: String,
    )

    private data class CachedIdentityToken(
        val token: String,
        val distinctId: String,
        val appId: String,
    )
}

package com.posthog.internal

import com.posthog.PostHogInternal
import com.posthog.PostHogVisibleForTesting
import com.posthog.vendor.uuid.TimeBasedEpochGenerator
import java.util.UUID

/**
 * Class that manages the Session ID
 */
@PostHogInternal
public object PostHogSessionManager {
    private val sessionLock = Any()

    // do not move to companion object, otherwise sessionId will be null
    private val sessionIdNone = UUID(0, 0)

    private var sessionId = sessionIdNone

    private var dateProvider: PostHogDateProvider? = null

    public fun setDateProvider(dateProvider: PostHogDateProvider) {
        this.dateProvider = dateProvider
    }

    /**
     * Timestamp (in milliseconds) when the current session was started.
     * Reset to 0 when the session ends.
     */
    private var sessionStartedAt: Long = 0L

    /**
     * Timestamp (in milliseconds) of the last user activity on the current session.
     * Used to detect 30-minute inactivity rotation. Reset to 0 when the session ends.
     */
    private var sessionActivityTimestamp: Long = 0L

    @Volatile
    public var isReactNative: Boolean = false

    @Volatile
    private var isAppInBackground: Boolean = false

    @Volatile
    private var onSessionIdChangedListener: (() -> Unit)? = null

    /**
     * Update the foreground/background state. Set from lifecycle callbacks to control
     * whether an expired session rotates (foreground) or is cleared (background) on read.
     */
    public fun setAppInBackground(inBackground: Boolean) {
        isAppInBackground = inBackground
    }

    /**
     * Registered by PostHog.setup; invoked after getActiveSessionId rotates the session
     * silently, so the session replay handler can react to the change.
     */
    public fun setOnSessionIdChangedListener(listener: (() -> Unit)?) {
        onSessionIdChangedListener = listener
    }

    public fun startSession() {
        synchronized(sessionLock) {
            // Re-check inside the lock — RN flag is set once at setup but checking
            // here keeps state consistent with the lock's invariants.
            if (isReactNative || sessionId != sessionIdNone) return
            val now = dateProvider?.currentTimeMillis() ?: System.currentTimeMillis()
            sessionId = TimeBasedEpochGenerator.generate()
            sessionStartedAt = now
            sessionActivityTimestamp = now
        }
    }

    public fun endSession() {
        synchronized(sessionLock) {
            if (isReactNative) return
            clearLocked()
        }
    }

    /**
     * Returns the timestamp (in milliseconds) when the current session was started,
     * or 0 if no session is active.
     */
    @PostHogVisibleForTesting
    public fun getSessionStartedAt(): Long {
        synchronized(sessionLock) {
            return sessionStartedAt
        }
    }

    /**
     * Returns true if the current session has been active for longer than 24 hours.
     */
    public fun isSessionExceedingMaxDuration(currentTimeMillis: Long): Boolean {
        synchronized(sessionLock) {
            return isMaxExpired(currentTimeMillis)
        }
    }

    private const val SESSION_MAX_DURATION = (1000L * 60 * 60 * 24) // 24 hours
    private const val SESSION_INACTIVITY_DURATION = (1000L * 60 * 30) // 30 minutes

    // Both helpers must be called while holding sessionLock — they read mutable fields
    // without taking the lock themselves to avoid nested-lock complexity.
    private fun isIdle(now: Long): Boolean =
        sessionActivityTimestamp > 0L &&
            (sessionActivityTimestamp + SESSION_INACTIVITY_DURATION) <= now

    private fun isMaxExpired(now: Long): Boolean =
        sessionStartedAt > 0L &&
            (sessionStartedAt + SESSION_MAX_DURATION) <= now

    private fun rotateLocked(now: Long): UUID {
        sessionId = TimeBasedEpochGenerator.generate()
        sessionStartedAt = now
        sessionActivityTimestamp = now
        return sessionId
    }

    private fun clearLocked() {
        sessionId = sessionIdNone
        sessionStartedAt = 0L
        sessionActivityTimestamp = 0L
    }

    public fun getActiveSessionId(): UUID? {
        var sessionChanged = false
        var tempSessionId: UUID?
        synchronized(sessionLock) {
            if (sessionId == sessionIdNone || isReactNative) {
                tempSessionId = if (sessionId != sessionIdNone) sessionId else null
            } else {
                val now = dateProvider?.currentTimeMillis() ?: System.currentTimeMillis()
                // Check inactivity first, then max-duration (mirror iOS order).
                if (isIdle(now) || isMaxExpired(now)) {
                    sessionChanged = true
                    tempSessionId =
                        if (isAppInBackground) {
                            clearLocked()
                            null
                        } else {
                            rotateLocked(now)
                        }
                } else {
                    tempSessionId = sessionId
                }
            }
        }
        if (sessionChanged) {
            onSessionIdChangedListener?.invoke()
        }
        return tempSessionId
    }

    /**
     * Marks user activity on the current session. Mirrors iOS touchSession():
     * if the session has gone idle past SESSION_INACTIVITY_DURATION, rotates it;
     * otherwise just refreshes the activity timestamp.
     *
     * Called from lifecycle transitions, replay touch interception, and event capture.
     * No-op when backgrounded so background events don't keep a dead session alive.
     */
    public fun touchSession() {
        var sessionChanged = false
        synchronized(sessionLock) {
            if (isReactNative || isAppInBackground || sessionId == sessionIdNone) return@synchronized
            val now = dateProvider?.currentTimeMillis() ?: System.currentTimeMillis()
            if (isIdle(now)) {
                rotateLocked(now)
                sessionChanged = true
            } else {
                sessionActivityTimestamp = now
            }
        }
        if (sessionChanged) {
            onSessionIdChangedListener?.invoke()
        }
    }

    public fun setSessionId(sessionId: UUID) {
        synchronized(sessionLock) {
            val now = dateProvider?.currentTimeMillis() ?: System.currentTimeMillis()
            this.sessionId = sessionId
            // Stamp the start so an externally-set session participates in the 24h
            // expiry check; without it sessionStartedAt stays 0 and never expires.
            sessionStartedAt = now
            sessionActivityTimestamp = now
        }
    }

    public fun isSessionActive(): Boolean {
        var active: Boolean
        synchronized(sessionLock) {
            active = sessionId != sessionIdNone
        }
        return active
    }
}

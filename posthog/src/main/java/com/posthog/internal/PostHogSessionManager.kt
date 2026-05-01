package com.posthog.internal

import com.posthog.PostHogInternal
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

    @Volatile
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

    // Defaults to true so an expired session before the first onStart is cleared rather
    // than silently rotated against a process that has no UI yet.
    @Volatile
    private var isAppInBackground: Boolean = true

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
    internal fun setOnSessionIdChangedListener(listener: (() -> Unit)?) {
        onSessionIdChangedListener = listener
    }

    public fun startSession() {
        var sessionChanged = false
        synchronized(sessionLock) {
            if (isReactNative || sessionId != sessionIdNone) return@synchronized
            val now = dateProvider?.currentTimeMillis() ?: System.currentTimeMillis()
            sessionId = TimeBasedEpochGenerator.generate()
            sessionStartedAt = now
            sessionActivityTimestamp = now
            sessionChanged = true
        }
        if (sessionChanged) {
            onSessionIdChangedListener?.invoke()
        }
    }

    public fun endSession() {
        var sessionChanged = false
        synchronized(sessionLock) {
            if (isReactNative || sessionId == sessionIdNone) return@synchronized
            clearLocked()
            sessionChanged = true
        }
        if (sessionChanged) {
            onSessionIdChangedListener?.invoke()
        }
    }

    /**
     * Returns the timestamp (in milliseconds) when the current session was started,
     * or 0 if no session is active. Test-only.
     */
    internal fun getSessionStartedAt(): Long {
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

    // Caller must hold sessionLock.
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
     * Marks user activity on the current session: rotates if idle past
     * [SESSION_INACTIVITY_DURATION], otherwise refreshes the activity timestamp.
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
        var sessionChanged = false
        synchronized(sessionLock) {
            // Re-asserting the same id (e.g. RN syncing the active id on every event) must not
            // reset the 24h max-duration or 30-min inactivity clocks — sessions would never expire.
            if (this.sessionId == sessionId) return@synchronized
            val now = dateProvider?.currentTimeMillis() ?: System.currentTimeMillis()
            this.sessionId = sessionId
            sessionStartedAt = now
            sessionActivityTimestamp = now
            sessionChanged = true
        }
        if (sessionChanged) {
            onSessionIdChangedListener?.invoke()
        }
    }

    public fun isSessionActive(): Boolean {
        var active: Boolean
        synchronized(sessionLock) {
            active = sessionId != sessionIdNone
        }
        return active
    }

    /**
     * Read-only sibling of [getActiveSessionId]: skips the expiry checks so callers reacting
     * to a session-id change can read the new id without risking a re-entrant rotation that
     * would re-fire the listener.
     */
    public fun peekSessionId(): UUID? {
        synchronized(sessionLock) {
            return if (sessionId == sessionIdNone) null else sessionId
        }
    }
}

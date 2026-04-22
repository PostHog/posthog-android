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
        if (isReactNative) {
            // RN manages its own session
            return
        }

        synchronized(sessionLock) {
            if (sessionId == sessionIdNone) {
                sessionId = TimeBasedEpochGenerator.generate()
                sessionStartedAt = dateProvider?.currentTimeMillis() ?: System.currentTimeMillis()
            }
        }
    }

    public fun endSession() {
        if (isReactNative) {
            // RN manages its own session
            return
        }

        synchronized(sessionLock) {
            sessionId = sessionIdNone
            sessionStartedAt = 0L
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
            return sessionStartedAt > 0L &&
                (sessionStartedAt + SESSION_MAX_DURATION) <= currentTimeMillis
        }
    }

    private const val SESSION_MAX_DURATION = (1000L * 60 * 60 * 24) // 24 hours

    public fun getActiveSessionId(): UUID? {
        var sessionChanged = false
        var tempSessionId: UUID?
        synchronized(sessionLock) {
            if (sessionId == sessionIdNone || isReactNative) {
                tempSessionId = if (sessionId != sessionIdNone) sessionId else null
            } else {
                val now = dateProvider?.currentTimeMillis() ?: System.currentTimeMillis()
                val expired = sessionStartedAt > 0L && (sessionStartedAt + SESSION_MAX_DURATION) <= now
                if (expired) {
                    sessionChanged = true
                    if (isAppInBackground) {
                        sessionId = sessionIdNone
                        sessionStartedAt = 0L
                        tempSessionId = null
                    } else {
                        sessionId = TimeBasedEpochGenerator.generate()
                        sessionStartedAt = now
                        tempSessionId = sessionId
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

    public fun setSessionId(sessionId: UUID) {
        synchronized(sessionLock) {
            this.sessionId = sessionId
            // Stamp the start so an externally-set session participates in the 24h
            // expiry check; without it sessionStartedAt stays 0 and never expires.
            sessionStartedAt = dateProvider?.currentTimeMillis() ?: System.currentTimeMillis()
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

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

    public var dateProvider: PostHogDateProvider = PostHogDeviceDateProvider()

    /**
     * Timestamp (in milliseconds) when the current session was started.
     * Reset to 0 when the session ends.
     */
    private var sessionStartedAt: Long = 0L

    @Volatile
    public var isReactNative: Boolean = false

    public fun startSession() {
        if (isReactNative) {
            // RN manages its own session
            return
        }

        synchronized(sessionLock) {
            if (sessionId == sessionIdNone) {
                sessionId = TimeBasedEpochGenerator.generate()
                sessionStartedAt = dateProvider.currentTimeMillis()
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
     * Atomically ends the current session and starts a new one.
     * This is used when the session exceeds the maximum allowed duration (e.g. 24 hours).
     */
    public fun rotateSession() {
        if (isReactNative) {
            // RN manages its own session
            return
        }

        synchronized(sessionLock) {
            sessionId = TimeBasedEpochGenerator.generate()
            sessionStartedAt = dateProvider.currentTimeMillis()
        }
    }

    /**
     * Returns the timestamp (in milliseconds) when the current session was started,
     * or 0 if no session is active.
     */
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

    private val SESSION_MAX_DURATION = (1000L * 60 * 60 * 24) // 24 hours

    public fun getActiveSessionId(): UUID? {
        var tempSessionId: UUID?
        synchronized(sessionLock) {
            tempSessionId = if (sessionId != sessionIdNone) sessionId else null
        }
        return tempSessionId
    }

    public fun setSessionId(sessionId: UUID) {
        // RN can only set its own session id directly
        synchronized(sessionLock) {
            this.sessionId = sessionId
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

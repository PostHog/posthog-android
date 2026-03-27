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

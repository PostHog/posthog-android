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
    private var sessionStartTime: Long = 0

    public fun startSession() {
        synchronized(sessionLock) {
            if (sessionId == sessionIdNone) {
                sessionId = TimeBasedEpochGenerator.generate()
                sessionStartTime = System.currentTimeMillis()
            }
        }
    }

    public fun endSession() {
        synchronized(sessionLock) {
            sessionId = sessionIdNone
            sessionStartTime = 0
        }
    }

    public fun getActiveSessionId(): UUID? {
        var tempSessionId: UUID?
        synchronized(sessionLock) {
            tempSessionId = if (sessionId != sessionIdNone) sessionId else null
        }
        return tempSessionId
    }

    public fun getActiveSessionStartTime(): Long? {
        var tempSessionStartTime: Long?
        synchronized(sessionLock) {
            tempSessionStartTime = if (sessionStartTime != 0L) sessionStartTime else null
        }
        return tempSessionStartTime
    }

    public fun setSessionId(sessionId: UUID) {
        synchronized(sessionLock) {
            this.sessionId = sessionId
            this.sessionStartTime = System.currentTimeMillis()
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

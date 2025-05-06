package com.posthog.internal

import com.posthog.PostHogInternal
import com.posthog.vendor.uuid.TimeBasedEpochGenerator
import java.util.UUID

public const val MAX_SESSION_AGE_MILLIS: Int = 24 * 60 * 60 * 60

/**
 * Class that manages the Session ID
 */
@PostHogInternal
public object PostHogSessionManager {
    private val sessionLock = Any()

    // do not move to companion object, otherwise sessionId will be null
    private val sessionIdNone = UUID(0, 0)

    private var sessionId = sessionIdNone

    public fun startSession(sessionStartTime: Long? = null) {
        synchronized(sessionLock) {
            if (sessionId == sessionIdNone) {
                sessionId = if (sessionStartTime != null) {
                    TimeBasedEpochGenerator.generate(sessionStartTime)
                } else {
                    TimeBasedEpochGenerator.generate()
                }
            }
        }
    }

    public fun endSession() {
        synchronized(sessionLock) {
            sessionId = sessionIdNone
        }
    }

    public fun getActiveSessionId(): UUID? {
        var tempSessionId: UUID?
        synchronized(sessionLock) {
            tempSessionId = if (sessionId != sessionIdNone) sessionId else null
        }
        return tempSessionId
    }

    public fun setSessionId(sessionId: UUID) {
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

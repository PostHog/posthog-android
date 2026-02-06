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

    // do not move to companion object, otherwise sessionId clearwill be null
    private val sessionIdNone = UUID(0, 0)

    private var sessionId = sessionIdNone

    @Volatile
    internal var isReactNative: Boolean = false

    public fun startSession() {
        if (isReactNative) {
            // RN manages its own session
            return
        }

        synchronized(sessionLock) {
            if (sessionId == sessionIdNone) {
                sessionId = TimeBasedEpochGenerator.generate()
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

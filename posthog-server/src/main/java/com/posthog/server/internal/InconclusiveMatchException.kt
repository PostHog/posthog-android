package com.posthog.server.internal

import com.posthog.server.PostHogUnresolvedFlagReason

/**
 * Exception thrown when flag evaluation cannot be determined locally
 */
internal class InconclusiveMatchException(
    message: String,
    val reason: PostHogUnresolvedFlagReason,
    cause: Throwable? = null,
) : Exception(message, cause)

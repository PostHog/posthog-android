package com.posthog.server.internal

import com.posthog.server.PostHogUnresolvedFlagReason

/**
 * Exception thrown when flag evaluation cannot be determined locally.
 *
 * It is a control-flow signal that callers log by message only, so it skips the stack trace.
 */
internal class InconclusiveMatchException(
    message: String,
    val reason: PostHogUnresolvedFlagReason,
    cause: Throwable? = null,
) : Exception(message, cause, false, false)

// When several causes apply to one flag, the spec reports the first reason in this order, so every
// SDK picks the same reason whatever order it inspects the conditions and filters in.
private val UNRESOLVED_REASON_PRECEDENCE =
    listOf(
        PostHogUnresolvedFlagReason.EXPERIENCE_CONTINUITY,
        PostHogUnresolvedFlagReason.UNSUPPORTED_DEFINITION,
        PostHogUnresolvedFlagReason.UNRESOLVED_DEPENDENCY,
        PostHogUnresolvedFlagReason.MISSING_CONTEXT,
    )

internal fun pickUnresolvedReason(
    current: PostHogUnresolvedFlagReason?,
    next: PostHogUnresolvedFlagReason,
): PostHogUnresolvedFlagReason =
    if (current == null || UNRESOLVED_REASON_PRECEDENCE.indexOf(next) < UNRESOLVED_REASON_PRECEDENCE.indexOf(current)) {
        next
    } else {
        current
    }

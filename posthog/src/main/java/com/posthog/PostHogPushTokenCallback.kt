package com.posthog

/**
 * Error reason when push token registration fails.
 */
public enum class PostHogPushTokenError {
    /** SDK is disabled (e.g. [PostHogConfig.optOut] or not started). */
    SDK_DISABLED,

    /** Token string was blank. */
    BLANK_TOKEN,

    /** Firebase project ID was blank. */
    BLANK_FIREBASE_PROJECT_ID,

    /** Internal config was null. */
    CONFIG_NULL,

    /** Network failure or no internet (e.g. connection refused, timeout, unknown host). */
    NETWORK_ERROR,

    /** Server rejected the request as invalid (e.g. 400 Bad Request, 404 Not Found). */
    INVALID_INPUT,

    /** Server returned 401 Unauthorized. */
    UNAUTHORIZED,

    /** Server error (5xx). */
    SERVER_ERROR,

    /** Other unexpected error during registration. */
    OTHER,
}

/**
 * Callback for push token registration results.
 * @param error `null` when registration succeeded or was skipped (e.g. token unchanged); otherwise the failure reason.
 * @param throwable When [error] is one of [PostHogPushTokenError.NETWORK_ERROR], [PostHogPushTokenError.INVALID_INPUT], [PostHogPushTokenError.UNAUTHORIZED], [PostHogPushTokenError.SERVER_ERROR], or [PostHogPushTokenError.OTHER], the exception that caused the failure; otherwise `null`.
 */
public fun interface PostHogPushTokenCallback {
    public fun onComplete(
        error: PostHogPushTokenError?,
        throwable: Throwable?,
    )
}

package com.posthog.android.replay

/**
 * Marks a session-replay API that exists only for first-party PostHog wrapper
 * SDKs (e.g. posthog-flutter) to drive out-of-engine capture. It is not a
 * public API: it has no source/binary stability guarantees and can change or
 * be removed without a major-version bump. App code should never opt in.
 */
@RequiresOptIn(
    message =
        "Internal PostHog session-replay API for first-party SDK integrations only. " +
            "Not covered by semantic versioning; do not use from app code.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
public annotation class PostHogInternalReplayApi

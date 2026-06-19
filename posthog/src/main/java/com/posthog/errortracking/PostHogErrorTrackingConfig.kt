package com.posthog.errortracking

import com.posthog.PostHog

/**
 * Configuration for PostHog error tracking.
 */
public class PostHogErrorTrackingConfig
    @JvmOverloads
    public constructor(
        /**
         * Enable autocapture of exceptions
         * This feature installs an uncaught exception handler (Thread.UncaughtExceptionHandler) that will capture exceptions
         *
         * Disabled by default
         *
         * You can manually capture exceptions by calling [PostHog.captureException]
         */
        public var autoCapture: Boolean = false,
        /**
         * List of package names to be considered inApp frames for error tracking
         *
         * inApp Example:
         * inAppIncludes=["com.yourapp"]
         * All Exception stacktrace frames that start with com.yourapp will be considered inApp*
         *
         * On Android only frames coming from the app's package name will be considered inApp by default
         * On Android, We add your app's package name to this list automatically (read from applicationId at runtime)
         *
         * If this list of package names is empty, all frames will be considered inApp
         */
        public val inAppIncludes: MutableList<String> = mutableListOf(),
        /**
         * Configuration for exception steps (breadcrumb-style context records attached to
         * every captured `$exception` event as `$exception_steps`).
         *
         * Record steps with [PostHog.addExceptionStep].
         */
        public val exceptionSteps: PostHogExceptionStepsConfig = PostHogExceptionStepsConfig(),
        /**
         * Throwable classes to skip during capture. For each captured throwable, the
         * SDK walks the cause chain and drops the `$exception` event when any link is
         * an instance of an entry in this list. Matching uses [Class.isInstance], so
         * R8 / ProGuard renames don't break the filter. The downstream uncaught
         * exception handler still runs.
         *
         * Defaults to empty.
         */
        public val ignoredExceptionTypes: MutableList<Class<out Throwable>> = mutableListOf(),
    )

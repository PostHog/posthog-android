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
         * Throwable classes to drop during capture. The throwable and every cause in
         * its chain are matched via [Class.isInstance]. Also applies to `$exception`
         * events sent through the generic capture path (matched by class name).
         *
         * Defaults to empty.
         */
        public val ignoredExceptionTypes: MutableList<Class<out Throwable>> = mutableListOf(),
        /**
         * Capture native (NDK) crashes from previous runs of the app.
         *
         * Android only, requires Android 12 (API 31). On startup the SDK reads the
         * native crash records the OS kept for the app and captures an `$exception`
         * event per crash, with raw stack frames for server-side symbolication
         * against uploaded `.so` debug symbols.
         *
         * Exception autocapture must also be enabled in the project's error tracking
         * settings (the same remote toggle that gates [autoCapture]).
         *
         * Disabled by default
         */
        public var captureNativeCrashes: Boolean = false,
    )

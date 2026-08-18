package com.posthog.errortracking

import com.posthog.PostHog

/**
 * Configuration for PostHog error tracking.
 */
public class PostHogErrorTrackingConfig
    @JvmOverloads
    // Do NOT add new options as constructor params: appending even a defaulted param rewrites the
    // Kotlin `$default` synthetic constructor descriptor, so already-compiled consumers hit a
    // NoSuchMethodError on `PostHogErrorTrackingConfig()`. New options go in the class body.
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
         *
         * Matched against the RUNTIME class name, before any symbolication. On a minified build
         * (ProGuard/R8) the runtime names are the obfuscated ones, so prefixes written against your
         * source packages generally will not match; PostHog also re-derives `in_app` server-side
         * after deobfuscation, which can override what the SDK sent.
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
    ) {
        /**
         * List of package names to be excluded from inApp frames for error tracking
         *
         * inApp Example:
         * inAppExcludes=["com.thirdparty"]
         * All Exception stacktrace frames that start with com.thirdparty will NOT be considered inApp
         *
         * Excludes win over [inAppIncludes]: a frame matching an exclude prefix is never inApp,
         * even if it also matches an include prefix.
         *
         * Same matching caveats as [inAppIncludes]: prefixes are compared against the RUNTIME class
         * name before any symbolication, so on minified (ProGuard/R8) builds an exclude written
         * against your source packages generally will not match, and PostHog's server-side
         * deobfuscation may reclassify frames as `in_app` afterwards regardless of what the SDK
         * sent. Making excludes survive deobfuscation needs a server-side in-app contract
         * (follow-up); until then treat this as best-effort on non-minified builds.
         *
         * Defaults to empty.
         */
        public val inAppExcludes: MutableList<String> = mutableListOf()
    }

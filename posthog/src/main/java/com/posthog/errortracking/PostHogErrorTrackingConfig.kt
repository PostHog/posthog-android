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
         * Fully qualified class names of throwables to skip during autocapture.
         *
         * Whenever the uncaught-exception handler fires, the throwable (and every cause
         * in its chain) is checked against this list by class name. If any link matches,
         * the SDK will not emit a `$exception` event — but the next exception handler in
         * the chain is still invoked, so the process termination / RN red-box / etc.
         * behaves as before.
         *
         * The primary use case is React Native apps that already capture fatal JS errors
         * via `@posthog/react-native-plugin`'s JS-side autocapture: React Native rethrows
         * the same fatal JS error on the native side as
         * `com.facebook.react.common.JavascriptException`, and posthog-android would
         * otherwise emit a second `$exception` event for the same logical error.
         *
         * Setting `ignoredExceptionTypes = mutableListOf("com.facebook.react.common.JavascriptException")`
         * suppresses the native duplicate so the JS-captured event is the single source
         * of truth. Mirrors `sentry-android`'s `addIgnoredExceptionForType(...)`.
         *
         * The matching is purely class-name based; no `Class<*>` objects are loaded, so
         * apps that don't have React Native (or any other optional dependency) on their
         * classpath are not affected.
         *
         * Defaults to empty.
         */
        public val ignoredExceptionTypes: MutableList<String> = mutableListOf(),
    )

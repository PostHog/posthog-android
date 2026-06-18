package com.posthog.errortracking

/**
 * Configuration for exception steps: breadcrumb-style context records that are
 * attached to every captured `$exception` event as `$exception_steps`.
 *
 * Record steps with [com.posthog.PostHog.addExceptionStep].
 */
public class PostHogExceptionStepsConfig
    @JvmOverloads
    public constructor(
        /**
         * Enable recording and attaching exception steps.
         *
         * When disabled, [com.posthog.PostHog.addExceptionStep] is a no-op and nothing is attached.
         *
         * Enabled by default.
         */
        public var enabled: Boolean = true,
        /**
         * Total UTF-8 byte budget for the rolling step buffer. When adding a step would
         * exceed the budget, the oldest steps are evicted until the total fits. A single
         * step larger than the budget is rejected outright.
         *
         * Defaults to 32768 (32 KiB).
         */
        public var maxBytes: Int = 32768,
    )

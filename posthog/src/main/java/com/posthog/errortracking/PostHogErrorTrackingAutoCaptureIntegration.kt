package com.posthog.errortracking

import com.posthog.PostHogConfig
import com.posthog.PostHogIntegration
import com.posthog.PostHogInterface
import com.posthog.internal.errortracking.PostHogThrowable
import com.posthog.internal.errortracking.UncaughtExceptionHandlerAdapter

public class PostHogErrorTrackingAutoCaptureIntegration : PostHogIntegration, Thread.UncaughtExceptionHandler {
    private val config: PostHogConfig
    private val adapterExceptionHandler: UncaughtExceptionHandlerAdapter
    private var defaultExceptionHandler: Thread.UncaughtExceptionHandler? = null
    private var postHog: PostHogInterface? = null

    public constructor(config: PostHogConfig) {
        this.config = config
        this.adapterExceptionHandler = UncaughtExceptionHandlerAdapter.Adapter.getInstance()
    }

    internal constructor(config: PostHogConfig, adapterExceptionHandler: UncaughtExceptionHandlerAdapter) {
        this.config = config
        this.adapterExceptionHandler = adapterExceptionHandler
    }

    private companion object {
        @Volatile
        private var integrationInstalled = false
    }

    override fun install(postHog: PostHogInterface) {
        this.postHog = postHog

        if (integrationInstalled) {
            return
        }

        val autocaptureExceptionsEnabled = config.remoteConfigHolder?.isAutocaptureExceptionsEnabled() ?: false
        if (!autocaptureExceptionsEnabled) {
            return
        }

        val currentExceptionHandler = adapterExceptionHandler.getDefaultUncaughtExceptionHandler()

        if (currentExceptionHandler != null) {
            if (currentExceptionHandler !is PostHogErrorTrackingAutoCaptureIntegration) {
                defaultExceptionHandler = currentExceptionHandler
                installHandler()
            }
            // we don't install if the handler is us already
        } else {
            defaultExceptionHandler = null
            installHandler()
        }
    }

    private fun installHandler() {
        adapterExceptionHandler.setDefaultUncaughtExceptionHandler(this)
        integrationInstalled = true
        config.logger.log("Exception autocapture is enabled.")
    }

    override fun uninstall() {
        if (!integrationInstalled) {
            return
        }
        adapterExceptionHandler.setDefaultUncaughtExceptionHandler(defaultExceptionHandler)
        integrationInstalled = false
        config.logger.log("Exception autocapture is disabled.")
    }

    override fun onRemoteConfig() {
        val autocaptureExceptionsEnabled = config.remoteConfigHolder?.isAutocaptureExceptionsEnabled() ?: false
        if (autocaptureExceptionsEnabled) {
            postHog?.let { install(it) }
        } else {
            uninstall()
        }
    }

    override fun uncaughtException(
        thread: Thread,
        throwable: Throwable,
    ) {
        postHog?.let { postHog ->
            if (!isIgnored(throwable)) {
                postHog.captureException(PostHogThrowable(throwable, thread))
                postHog.flush()
            } else {
                config.logger.log(
                    "Skipping autocapture for ignored exception type: ${throwable.javaClass.name}",
                )
            }
        }

        // Always chain to the next handler so the process terminates / RN's red-box
        // surfaces / etc. behave the same way as before, regardless of whether we
        // emitted a $exception event.
        defaultExceptionHandler?.uncaughtException(thread, throwable)
    }

    private fun isIgnored(throwable: Throwable): Boolean {
        val ignored = config.errorTrackingConfig.ignoredExceptionTypes
        if (ignored.isEmpty()) return false

        // Walk the cause chain so a wrapped exception (e.g. RuntimeException wrapping
        // a JavascriptException) is matched too. The seen-set guards against the
        // pathological self-referential cause chains that some JVM libs construct.
        var current: Throwable? = throwable
        val seen = mutableSetOf<Throwable>()
        while (current != null && seen.add(current)) {
            if (ignored.contains(current.javaClass.name)) return true
            current = current.cause
        }
        return false
    }
}

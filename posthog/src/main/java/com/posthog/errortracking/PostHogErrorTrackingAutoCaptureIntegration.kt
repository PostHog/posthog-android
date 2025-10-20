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
        if (integrationInstalled) {
            return
        }

        if (!config.errorTrackingConfig.autoCapture) {
            return
        }

        this.postHog = postHog

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
        postHog = null
        config.logger.log("Exception autocapture is disabled.")
    }

    override fun uncaughtException(
        thread: Thread,
        throwable: Throwable,
    ) {
        postHog?.let { postHog ->
            postHog.captureException(PostHogThrowable(throwable, thread))
            postHog.flush()
        }

        defaultExceptionHandler?.uncaughtException(thread, throwable)
    }
}

package com.posthog.exceptions

import com.posthog.PostHogConfig
import com.posthog.PostHogIntegration
import com.posthog.PostHogInterface
import com.posthog.internal.exceptions.PostHogThrowable
import com.posthog.internal.exceptions.UncaughtExceptionHandlerAdapter

public class PostHogExceptionAutoCaptureIntegration : PostHogIntegration, Thread.UncaughtExceptionHandler {
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
        this.postHog = postHog

        if (!config.exceptionAutocapture) {
            return
        }

        val currentExceptionHandler = adapterExceptionHandler.getDefaultUncaughtExceptionHandler()

        if (currentExceptionHandler != null) {
            if (currentExceptionHandler !is PostHogExceptionAutoCaptureIntegration) {
                defaultExceptionHandler = currentExceptionHandler
            }
        } else {
            defaultExceptionHandler = null
        }
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

    override fun uncaughtException(
        thread: Thread,
        throwable: Throwable,
    ) {
        postHog?.let { postHog ->
            postHog.captureException(PostHogThrowable(throwable, thread))
            // TODO: flush has to await the queue to be processed
            postHog.flush()
        }

        defaultExceptionHandler?.uncaughtException(thread, throwable)
    }
}

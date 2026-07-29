package com.posthog.errortracking

import com.posthog.PostHogConfig
import com.posthog.PostHogIntegration
import com.posthog.PostHogInterface
import com.posthog.internal.errortracking.PostHogThrowable
import com.posthog.internal.errortracking.UncaughtExceptionHandlerAdapter
import java.util.concurrent.atomic.AtomicBoolean

public class PostHogErrorTrackingAutoCaptureIntegration : PostHogIntegration, Thread.UncaughtExceptionHandler {
    private val config: PostHogConfig
    private val adapterExceptionHandler: UncaughtExceptionHandlerAdapter
    private var defaultExceptionHandler: Thread.UncaughtExceptionHandler? = null
    private var postHog: PostHogInterface? = null
    private var ownsInstallation = false

    public constructor(config: PostHogConfig) {
        this.config = config
        this.adapterExceptionHandler = UncaughtExceptionHandlerAdapter.Adapter.getInstance()
    }

    internal constructor(config: PostHogConfig, adapterExceptionHandler: UncaughtExceptionHandlerAdapter) {
        this.config = config
        this.adapterExceptionHandler = adapterExceptionHandler
    }

    private companion object {
        private val integrationInstalled = AtomicBoolean(false)
    }

    @Synchronized
    override fun install(postHog: PostHogInterface) {
        this.postHog = postHog

        if (integrationInstalled.get()) {
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
        if (!integrationInstalled.compareAndSet(false, true)) {
            return
        }
        ownsInstallation = true
        adapterExceptionHandler.setDefaultUncaughtExceptionHandler(this)
        config.logger.log("Exception autocapture is enabled.")
    }

    @Synchronized
    override fun uninstall() {
        if (!ownsInstallation) {
            return
        }
        try {
            adapterExceptionHandler.setDefaultUncaughtExceptionHandler(defaultExceptionHandler)
            config.logger.log("Exception autocapture is disabled.")
        } finally {
            ownsInstallation = false
            integrationInstalled.set(false)
        }
    }

    override fun onRemoteConfig(loaded: Boolean) {
        // Only react to a live config; a failed attempt applies no fresh values.
        if (!loaded) {
            return
        }
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
            postHog.captureException(PostHogThrowable(throwable, thread))
            postHog.flush()
        }

        defaultExceptionHandler?.uncaughtException(thread, throwable)
    }
}

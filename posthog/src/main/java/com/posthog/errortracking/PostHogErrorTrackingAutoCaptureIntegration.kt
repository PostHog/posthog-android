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

        // Local config is the primary gate (the remote check below is only a kill-switch).
        if (!config.errorTrackingConfig.autoCapture) {
            return
        }

        // Remote config is a kill-switch, not a gate: install by default and skip only when a
        // config that already exists — fetched this session or cached from a prior launch —
        // explicitly disables autocapture, keeping the first-launch window (before /flags) covered.
        val remoteConfig = config.remoteConfigHolder
        val hasRemoteConfig =
            remoteConfig?.hasRemoteConfigFetched() == true ||
                remoteConfig?.hasCachedErrorTrackingConfig() == true
        if (hasRemoteConfig && remoteConfig?.isAutocaptureExceptionsEnabled() == false) {
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

    override fun onRemoteConfig(loaded: Boolean) {
        // Only react to a live config; a failed attempt applies no fresh values, so leave the
        // default first-launch install in place rather than tearing it down until a real config
        // says otherwise.
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

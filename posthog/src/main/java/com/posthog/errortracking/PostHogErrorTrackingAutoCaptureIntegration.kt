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

    // Tracks whether we should capture, separate from whether we're linked into the handler chain.
    // We can't always unlink (a handler installed after us keeps us as its delegate), so a disabled
    // instance stays in the chain but dormant.
    @Volatile
    private var captureEnabled = false

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

        // Already linked into the chain: just resume capturing. Re-running the link logic while
        // we're a mid-chain delegate would point defaultExceptionHandler back at a handler that
        // delegates to us, looping uncaughtException until it StackOverflows.
        if (integrationInstalled.get()) {
            // Resume only if we own the chain link and the local gate is still on. A non-owning
            // instance isn't wired into the handler chain, so setting captureEnabled here would be
            // inert and misleading; and a same-instance resume must honor autoCapture toggled off
            // since install.
            if (ownsInstallation && config.errorTrackingConfig.autoCapture) {
                captureEnabled = true
            }
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
        if (!integrationInstalled.compareAndSet(false, true)) {
            return
        }
        ownsInstallation = true
        adapterExceptionHandler.setDefaultUncaughtExceptionHandler(this)
        captureEnabled = true
        config.logger.log("Exception autocapture is enabled.")
    }

    @Synchronized
    override fun uninstall() {
        if (!ownsInstallation) {
            return
        }
        // Stop capturing regardless of whether we can unlink.
        captureEnabled = false
        // Only unlink (and release ownership) if we're still the active handler. If something
        // installed after us, we stay linked as its delegate: captureEnabled=false keeps us dormant,
        // and holding the install flags stops a later re-enable from re-linking into a loop.
        if (adapterExceptionHandler.getDefaultUncaughtExceptionHandler() === this) {
            adapterExceptionHandler.setDefaultUncaughtExceptionHandler(defaultExceptionHandler)
            ownsInstallation = false
            integrationInstalled.set(false)
            // We're out of the chain now, so drop the delegate ref (a re-install re-reads it).
            // postHog is kept: onRemoteConfig re-enable calls install(postHog) on this instance.
            defaultExceptionHandler = null
            config.logger.log("Exception autocapture is disabled.")
        } else {
            // Can't unlink — a handler installed after us keeps us as its delegate. Stay dormant
            // (captureEnabled is already false) and keep delegating.
            config.logger.log("Exception autocapture is dormant (still linked below another handler).")
        }
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
        if (captureEnabled) {
            postHog?.let { postHog ->
                postHog.captureException(PostHogThrowable(throwable, thread))
                postHog.flush()
            }
        }

        // Always delegate: we may still be mid-chain even while dormant.
        defaultExceptionHandler?.uncaughtException(thread, throwable)
    }
}

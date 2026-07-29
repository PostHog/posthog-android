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

    internal companion object {
        private val integrationInstalled = AtomicBoolean(false)

        internal fun resetForTests() {
            integrationInstalled.set(false)
        }
    }

    @Synchronized
    override fun install(postHog: PostHogInterface) {
        this.postHog = postHog

        // Already linked into the chain (possibly dormant below a handler installed after us):
        // resume capturing in place. Re-running the link logic while we're a mid-chain delegate
        // would point defaultExceptionHandler back at a handler that delegates to us, looping
        // uncaughtException until it StackOverflows — so we never relink, only flip the gate.
        if (ownsInstallation) {
            if (canCapture()) {
                captureEnabled = true
                // Re-arm the process-wide flag: a dormant uninstall clears it, and while we
                // capture again a fresh instance must not also link on top of us.
                integrationInstalled.set(true)
            }
            return
        }

        // A different instance is already linked and armed; don't double-link into the chain.
        if (integrationInstalled.get()) {
            return
        }

        if (!canCapture()) {
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

    // Local config is the primary gate; remote config is only a kill-switch (below).
    private fun canCapture(): Boolean = config.errorTrackingConfig.autoCapture && !remoteKillSwitchActive()

    // Remote config is a kill-switch, not a gate: it blocks capture only when a config that
    // already exists — fetched this session or cached from a prior launch — explicitly disables
    // autocapture, keeping the first-launch window (before /flags) covered.
    private fun remoteKillSwitchActive(): Boolean {
        val remoteConfig = config.remoteConfigHolder ?: return false
        val hasRemoteConfig =
            remoteConfig.hasRemoteConfigFetched() || remoteConfig.hasCachedErrorTrackingConfig()
        return hasRemoteConfig && !remoteConfig.isAutocaptureExceptionsEnabled()
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
            // Can't unlink — a handler installed after us keeps us as its delegate. Stay linked as
            // a pass-through (captureEnabled is already false), but release the process-wide armed
            // flag so a fresh integration can take over autocapture. We keep ownsInstallation=true
            // so our own later re-enable resumes in place (above) instead of relinking into a loop.
            // ponytail: if this same dormant instance is re-enabled *after* a fresh instance armed
            // on top, both capture (duplicate events, not a loop). Requires a closed client to
            // still receive onRemoteConfig, which doesn't happen in practice.
            integrationInstalled.set(false)
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

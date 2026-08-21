package com.posthog.errortracking

import com.posthog.PostHogConfig
import com.posthog.PostHogIntegration
import com.posthog.PostHogInterface
import com.posthog.PostHogInternal
import com.posthog.internal.errortracking.PostHogCapturedThrowables
import com.posthog.internal.errortracking.PostHogThrowable
import com.posthog.internal.errortracking.UncaughtExceptionHandlerAdapter
import java.util.concurrent.atomic.AtomicBoolean

public class PostHogErrorTrackingAutoCaptureIntegration : PostHogIntegration, Thread.UncaughtExceptionHandler {
    private val config: PostHogConfig
    private val adapterExceptionHandler: UncaughtExceptionHandlerAdapter

    /**
     * Decides whether the handler may capture. Defaults to the Android/core gate: local
     * `errorTrackingConfig.autoCapture` with remote config acting only as a kill-switch. Layers that
     * decide autocapture purely from local config (e.g. the server SDK, which never fetches remote
     * config) supply their own gate.
     */
    private val enabledGate: () -> Boolean

    // @Volatile: read on the crashing thread in uncaughtException with no happens-before edge to
    // the install()/uninstall() writes; a pre-existing thread could otherwise see a stale null and
    // skip delegating to the app/system handler.
    @Volatile
    private var defaultExceptionHandler: Thread.UncaughtExceptionHandler? = null
    private var ownsInstallation = false

    // Tracks whether we should capture, separate from whether we're linked into the handler chain.
    // We can't always unlink (a handler installed after us keeps us as its delegate), so a disabled
    // instance stays in the chain but dormant.
    @Volatile
    private var captureEnabled = false

    /**
     * Where captured uncaught exceptions are delivered. Set on install. The core [PostHogInterface]
     * client and the stateless server client do not share a common capture supertype, so the handler
     * targets this minimal seam instead of a concrete client type.
     */
    private var captureTarget: CaptureTarget? = null

    public constructor(config: PostHogConfig) {
        this.config = config
        this.adapterExceptionHandler = UncaughtExceptionHandlerAdapter.Adapter.getInstance()
        this.enabledGate = { defaultGate() }
    }

    /**
     * Internal constructor allowing a custom [enabledGate]. Used by SDK layers (e.g. the server SDK)
     * that decide autocapture purely from local config without any remote-config round trip.
     *
     * Not part of the public API; visible only because of the multi-module architecture.
     */
    @PostHogInternal
    public constructor(config: PostHogConfig, enabledGate: () -> Boolean) {
        this.config = config
        this.adapterExceptionHandler = UncaughtExceptionHandlerAdapter.Adapter.getInstance()
        this.enabledGate = enabledGate
    }

    internal constructor(config: PostHogConfig, adapterExceptionHandler: UncaughtExceptionHandlerAdapter) {
        this.config = config
        this.adapterExceptionHandler = adapterExceptionHandler
        this.enabledGate = { defaultGate() }
    }

    internal constructor(
        config: PostHogConfig,
        adapterExceptionHandler: UncaughtExceptionHandlerAdapter,
        enabledGate: () -> Boolean,
    ) {
        this.config = config
        this.adapterExceptionHandler = adapterExceptionHandler
        this.enabledGate = enabledGate
    }

    /**
     * Minimal capture surface the uncaught handler needs. Both the core client and the stateless
     * server client can satisfy it, without sharing a public supertype. [capture] and [flush] are
     * separate so the handler can ask for everything pending to be sent as its last act.
     *
     * Not part of the public API; visible only because of the multi-module architecture.
     */
    @PostHogInternal
    public interface CaptureTarget {
        public fun capture(throwable: Throwable)

        public fun flush()
    }

    internal companion object {
        private val integrationInstalled = AtomicBoolean(false)

        internal fun resetForTests() {
            integrationInstalled.set(false)
        }
    }

    @Synchronized
    override fun install(postHog: PostHogInterface) {
        installWith(
            object : CaptureTarget {
                override fun capture(throwable: Throwable) {
                    postHog.captureException(throwable)
                }

                override fun flush() {
                    postHog.flush()
                }
            },
        )
    }

    /**
     * Installs the handler delivering captures to [target]. Used by SDK layers whose client is not a
     * core [PostHogInterface] (e.g. the server SDK).
     *
     * Not part of the public API; visible only because of the multi-module architecture.
     */
    @PostHogInternal
    @Synchronized
    public fun installWith(target: CaptureTarget) {
        this.captureTarget = target

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

    private fun canCapture(): Boolean = enabledGate()

    // Local config is the primary gate; remote config is only a kill-switch (below).
    private fun defaultGate(): Boolean = config.errorTrackingConfig.autoCapture && !remoteKillSwitchActive()

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
            // captureTarget is kept: an onRemoteConfig re-enable re-installs with it.
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
            captureTarget?.let { installWith(it) }
        } else {
            uninstall()
        }
    }

    override fun uncaughtException(
        thread: Thread,
        throwable: Throwable,
    ) {
        if (captureEnabled) {
            captureTarget?.let { target ->
                // Mark the throwable so post-crash log mirrors of this exact instance (e.g. a
                // shutdown hook logging the crash) don't re-report it — but never skip the capture
                // itself: this is the authoritative fatal/unhandled record for the crash and must not
                // be downgraded by an earlier handled capture of the same instance
                // (`logger.error(..., e); throw e`).
                PostHogCapturedThrowables.markAndCheck(throwable)
                target.capture(PostHogThrowable(throwable, thread))
                // Depending on the target's queue the capture above may only enqueue the event, so
                // this flush is its last chance to reach the network before the process goes down.
                // Targets whose enqueue is asynchronous make this a bounded blocking flush (see the
                // server SDK's target). Delivery stays best-effort under an immediate hard exit.
                target.flush()
            }
        }

        // Always delegate: we may still be mid-chain even while dormant.
        val previousHandler = defaultExceptionHandler
        if (previousHandler != null) {
            previousHandler.uncaughtException(thread, throwable)
        } else if (throwable !is ThreadDeath) {
            // No previous default handler: reproduce the JVM's built-in crash output that
            // ThreadGroup would have printed had we not installed ourselves as the default handler,
            // so opting into capture never hides crashes from stderr log collection. ThreadDeath is
            // excluded because ThreadGroup stays silent for it.
            System.err.print("Exception in thread \"${thread.name}\" ")
            throwable.printStackTrace(System.err)
        }
    }
}

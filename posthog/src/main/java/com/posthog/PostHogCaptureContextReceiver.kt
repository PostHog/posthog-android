package com.posthog

/** Optional SDK integration hook for capture-context transitions. */
@PostHogInternal
public interface PostHogCaptureContextReceiver {
    /**
     * Called synchronously before and after an identity, consent, screen, session or close change.
     * Calls may be nested or concurrent. Implementations must be thread-safe and must not block
     * on another thread. The same integrations receive both phases, including through close.
     *
     * @param inProgress True before the change; false when the change finishes, even on failure.
     */
    public fun onCaptureContextChange(inProgress: Boolean)
}

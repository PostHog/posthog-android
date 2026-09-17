package com.posthog

/**
 * SDK-only, integration-owned guard that rejects observations invalidated by capture-context changes.
 * Activate during install, deactivate during uninstall, and synchronously forward
 * [PostHogIntegration.onChange] to [invalidate].
 */
@PostHogInternal
public class PostHogCaptureGuard {
    private val lock = Any()
    private var epoch = 0L
    private var active = false

    public fun setActive(active: Boolean) {
        synchronized(lock) {
            epoch++
            this.active = active
        }
    }

    public fun invalidate() {
        synchronized(lock) {
            epoch++
        }
    }

    public fun generation(): Long? = synchronized(lock) { epoch.takeIf { active } }

    internal fun enqueueIfCurrent(
        expectedGeneration: Long,
        enqueue: () -> Boolean,
    ): Boolean =
        synchronized(lock) {
            if (!active || epoch != expectedGeneration) return false
            enqueue()
        }
}

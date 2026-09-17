package com.posthog

/**
 * SDK-only, integration-owned guard for observations that must not cross capture-context changes.
 * Activate during install, deactivate during uninstall, and synchronously forward both phases of
 * [PostHogCaptureContextReceiver.onCaptureContextChange] to [onCaptureContextChange].
 */
@PostHogInternal
public class PostHogCaptureGuard {
    private val lock = Any()
    private var epoch = 0L
    private var transitions = 0
    private var active = false

    public fun setActive(active: Boolean) {
        synchronized(lock) {
            epoch++
            this.active = active
        }
    }

    public fun onCaptureContextChange(inProgress: Boolean) {
        synchronized(lock) {
            epoch++
            transitions += if (inProgress) 1 else -1
        }
    }

    public fun generation(): Long? = synchronized(lock) { epoch.takeIf { active && transitions == 0 } }

    internal fun enqueueIfCurrent(
        expectedGeneration: Long,
        enqueue: () -> Boolean,
    ): Boolean =
        synchronized(lock) {
            if (!active || transitions != 0 || epoch != expectedGeneration) return false
            enqueue()
        }
}

internal inline fun <T> PostHogConfig?.withCaptureContextChange(block: () -> T): T {
    // Retain the same recipients through close(), which clears the SDK configuration.
    val integrations = this?.integrations?.filterIsInstance<PostHogCaptureContextReceiver>().orEmpty()
    integrations.notifyCaptureContextChange(true)
    return try {
        block()
    } finally {
        integrations.notifyCaptureContextChange(false)
    }
}

internal fun List<PostHogCaptureContextReceiver>.notifyCaptureContextChange(inProgress: Boolean) {
    forEach {
        try {
            it.onCaptureContextChange(inProgress)
        } catch (_: Throwable) {
            // Integration callbacks must never prevent identity or consent changes.
        }
    }
}

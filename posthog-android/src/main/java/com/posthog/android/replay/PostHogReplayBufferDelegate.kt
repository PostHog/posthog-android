package com.posthog.android.replay

/**
 * Delegate interface for controlling session replay buffering behavior.
 *
 * The replay queue is passive: it checks [isBuffering] on every `add()` and `flush()`,
 * and notifies the delegate after buffering a snapshot.
 */
internal interface PostHogReplayBufferDelegate {
    /**
     * Whether the replay queue should buffer snapshots instead of sending directly.
     * Checked on every `queue.add()` and `queue.flush()`.
     */
    val isBuffering: Boolean

    /**
     * Whether session recording is currently active. The queue drops snapshots routed to the
     * persisted queue while this is false, so a snapshot captured just before recording stopped
     * (e.g. a fresh remote config rejecting the cached flag) cannot leak to the network.
     */
    val isActive: Boolean

    /**
     * Called after a snapshot was added to the buffer.
     * The delegate should check threshold conditions and schedule
     * `replayQueue.migrateBufferToQueue()` on a background thread when
     * the minimum duration has been met.
     */
    fun onReplayBufferSnapshot(replayQueue: PostHogReplayQueue)

    /**
     * The masking generation the snapshot being added on the current thread was collected under,
     * or null when it carries none (e.g. a snapshot a wrapper SDK built). Read on the `add()` caller's thread.
     */
    fun currentCaptureMaskingGeneration(): Int? = null

    /**
     * Whether stricter masking dropped the buffer after a snapshot collected under [generation]
     * was taken, so the queue must discard it.
     */
    fun isMaskingGenerationStale(generation: Int): Boolean = false
}

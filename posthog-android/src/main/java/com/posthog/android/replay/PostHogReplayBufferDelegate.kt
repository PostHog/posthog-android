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
     * Called after a snapshot was added to the buffer.
     * The delegate should check threshold conditions and call
     * `replayQueue.migrateBufferToQueue()` when the minimum duration has been met.
     */
    fun onReplayBufferSnapshot(replayQueue: PostHogReplayQueue)
}

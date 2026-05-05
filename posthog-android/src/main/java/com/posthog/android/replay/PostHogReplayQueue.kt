package com.posthog.android.replay

import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import com.posthog.internal.PostHogQueueInterface
import java.io.File

/**
 * A replay queue that wraps an inner queue (for actual API sends) and a
 * [PostHogReplayBufferQueue] (for buffering snapshots until minimum session
 * duration is met).
 *
 * The queue is passive — it delegates all buffering decisions to a
 * [PostHogReplayBufferDelegate]. When [PostHogReplayBufferDelegate.isBuffering]
 * is true, snapshots are routed to the buffer and `flush()` calls are suppressed.
 */
internal class PostHogReplayQueue internal constructor(
    private val config: PostHogConfig,
    private val innerQueue: PostHogQueueInterface,
    replayStoragePrefix: String?,
) : PostHogQueueInterface {
    private val replayDir = replayStoragePrefix?.let { File(it, config.apiKey) }

    private val bufferQueue: PostHogReplayBufferQueue =
        PostHogReplayBufferQueue(
            config,
            if (replayStoragePrefix != null) {
                File("$replayStoragePrefix-buffer", config.apiKey)
            } else {
                File(System.getProperty("java.io.tmpdir"), "posthog-replay-buffer/${config.apiKey}")
            },
        )

    internal var bufferDelegate: PostHogReplayBufferDelegate? = null

    /**
     * The time span (in millis) of buffered snapshots (oldest to newest).
     */
    internal val bufferDurationMs: Long?
        get() = bufferQueue.bufferDurationMs

    /**
     * Number of events currently in the buffer.
     */
    internal val bufferDepth: Int
        get() = bufferQueue.depth

    /**
     * Approximate number of events currently in the inner replay queue directory.
     */
    internal val depth: Int
        get() = replayDir?.listFiles()?.size ?: 0

    override fun add(event: PostHogEvent) {
        if (bufferDelegate?.isBuffering == true) {
            bufferQueue.add(event)
            config.logger.log("Buffered replay event '${event.event}'. Buffer depth: ${bufferQueue.depth}")
            bufferDelegate?.onReplayBufferSnapshot(this)
        } else {
            innerQueue.add(event)
        }
    }

    override fun flush() {
        if (bufferDelegate?.isBuffering == true) {
            config.logger.log("Replay queue flush suppressed — still buffering")
            return
        }
        innerQueue.flush()
    }

    override fun start() {
        innerQueue.start()
    }

    override fun stop() {
        innerQueue.stop()
    }

    override fun clear() {
        innerQueue.clear()
        bufferQueue.clear()
    }

    /**
     * Migrates all buffered items to the inner replay queue.
     */
    internal fun migrateBufferToQueue() {
        val bufferedCount = bufferQueue.depth
        if (bufferedCount == 0) {
            config.logger.log("No buffered replay events to migrate")
            return
        }

        val migrated = bufferQueue.migrateAllTo(innerQueue)
        config.logger.log(
            "Migrated $migrated/$bufferedCount buffered replay events to replay queue.",
        )
    }

    /**
     * Discards all buffered replay events.
     */
    internal fun clearBuffer() {
        bufferQueue.clear()
        config.logger.log("Replay buffer cleared")
    }
}

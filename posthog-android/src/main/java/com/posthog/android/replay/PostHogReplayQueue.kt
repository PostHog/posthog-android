package com.posthog.android.replay

import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import com.posthog.internal.PostHogQueueInterface
import com.posthog.internal.executeSafely
import com.posthog.internal.submitSyncSafely
import java.io.File
import java.util.concurrent.ExecutorService

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
    private val innerQueue: PostHogQueueInterface<PostHogEvent>,
    replayStoragePrefix: String?,
    private val executor: ExecutorService,
) : PostHogQueueInterface<PostHogEvent> {
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
        if (bufferDelegate?.isBuffering != true) {
            if (shouldPersist()) {
                innerQueue.add(event)
            }
            return
        }

        executor.executeSafely {
            if (bufferDelegate?.isBuffering == true) {
                bufferQueue.add(event)
                config.logger.log("Buffered replay event '${event.event}'. Buffer depth: ${bufferQueue.depth}")
                bufferDelegate?.onReplayBufferSnapshot(this)
            } else if (shouldPersist()) {
                innerQueue.add(event)
            }
        }
    }

    // A delegate that reports recording inactive means a snapshot slipped through after recording
    // stopped (e.g. a fresh-false remote config); drop it rather than persist/send it. With no
    // delegate (recording not yet wired) the queue persists as before.
    private fun shouldPersist(): Boolean = bufferDelegate?.isActive != false

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
        clearBuffer()
    }

    /**
     * Migrates all currently buffered items to the inner replay queue.
     *
     * This is called by the buffer delegate after a buffered write completes.
     * It should be scheduled off the caller/UI thread because migration does disk IO.
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
        executor.submitSyncSafely {
            bufferQueue.clear()
            config.logger.log("Replay buffer cleared")
        }
    }
}

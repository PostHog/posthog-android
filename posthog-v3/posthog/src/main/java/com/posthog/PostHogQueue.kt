package com.posthog

import java.util.Date
import java.util.concurrent.Executors

// TODO: move to disk cache instead of memory cache (using PostHogStorage)
internal class PostHogQueue(private val config: PostHogConfig, private val storage: PostHogStorage, private val api: PostHogApi) {

    private val deque = ArrayDeque<PostHogEvent>()
    private val lock = Any()
    private var paused = false
    private var pausedUntil: Date? = null

    // https://github.com/square/tape/blob/master/tape/src/main/java/com/squareup/tape2/QueueFile.java
    @Volatile
    private var isFlushing = false

    private val executor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory())

    fun add(event: PostHogEvent) {
        if (deque.size >= config.maxQueueSize) {
            config.logger?.log("Queue is full, the event ${event.event} is dropped.")
            return
        }

        synchronized(lock) {
            deque.add(event)

            config.logger?.log("Queued event ${event.event}.")
        }

        flushIfOverThreshold()
    }

    private fun flushIfOverThreshold() {
        if (deque.size >= config.flushAt) {
            flush()
        }
    }

    private fun canFlush(): Boolean {
        if (paused) {
            config.logger?.log("Queue is paused.")
            return false
        }

        val pausedUntil = this.pausedUntil
        if (pausedUntil != null && pausedUntil.after(Date())) {
            config.logger?.log("Queue is paused until $pausedUntil")
            return false
        }

        if (isFlushing) {
            config.logger?.log("Queue is flushing.")
            return false
        }

        return true
    }

    private fun flush() {
        if (!canFlush()) {
            config.logger?.log("Cannot flush the Queue.")
            return
        }

        val events: List<PostHogEvent>
        synchronized(lock) {
            events = deque.take(config.maxBatchSize)
        }

        executor.execute {
            isFlushing = true

            try {
                api.batch(events)

                synchronized(lock) {
                    deque.removeAll(events)
                }
            } catch (e: Throwable) {
                config.logger?.log("Flushing failed: $e")
            }

            isFlushing = false
        }
    }
}

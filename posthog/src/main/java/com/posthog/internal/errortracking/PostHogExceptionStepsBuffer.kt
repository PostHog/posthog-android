package com.posthog.internal.errortracking

import com.google.gson.reflect.TypeToken
import com.posthog.internal.PostHogLogger
import com.posthog.internal.PostHogSerializer
import java.util.Date

/**
 * A rolling, byte-bounded FIFO buffer of breadcrumb-style exception steps.
 *
 * Each step is normalized to its JSON-safe wire form exactly once (using the same
 * serializer applied to event properties) before it is byte-counted, stored, and
 * later attached to an `$exception` event, so the byte budget reflects exactly what
 * is sent. The buffer is thread-safe and recording never throws.
 *
 * Android captures fatal crashes in-process via the uncaught-exception handler and
 * the resulting event rides the existing persisted event queue, so an in-memory
 * buffer is sufficient — no crash-durable persistence is required.
 */
internal class PostHogExceptionStepsBuffer(
    private val maxBytes: Int,
    private val serializer: PostHogSerializer,
    private val logger: PostHogLogger,
) {
    private val lock = Any()
    private val steps = ArrayDeque<Entry>()
    private var totalBytes = 0

    private class Entry(val step: Map<String, Any>, val bytes: Int)

    /**
     * Normalizes and appends a step synchronously on the calling thread, so a step
     * recorded just before a crash is buffered when the crash is captured. [timestamp]
     * is captured by the caller at call time. Thread-safe via [lock].
     */
    fun add(
        message: String,
        timestamp: Date,
        properties: Map<String, Any>?,
    ) {
        if (message.isBlank()) {
            logger.log("Exception step ignored: message is empty.")
            return
        }

        try {
            val raw = LinkedHashMap<String, Any>()
            properties?.forEach { (key, value) ->
                if (key == MESSAGE_KEY || key == TIMESTAMP_KEY) {
                    logger.log("Exception step property '$key' is reserved and was stripped.")
                } else {
                    raw[key] = value
                }
            }
            raw[MESSAGE_KEY] = message
            raw[TIMESTAMP_KEY] = timestamp

            val json = serializer.gson.toJson(raw, mapType)
            val bytes = json.toByteArray(Charsets.UTF_8).size
            if (bytes > maxBytes) {
                logger.log("Exception step skipped: it is larger than maxBytes ($maxBytes).")
                return
            }

            @Suppress("UNCHECKED_CAST")
            val normalized = serializer.deserializeString(json) as? Map<String, Any>
            if (normalized == null) {
                logger.log("Exception step skipped: could not normalize it to JSON.")
                return
            }

            synchronized(lock) {
                steps.addLast(Entry(normalized, bytes))
                totalBytes += bytes
                while (totalBytes > maxBytes && steps.isNotEmpty()) {
                    totalBytes -= steps.removeFirst().bytes
                }
            }
        } catch (e: Throwable) {
            logger.log("Exception step skipped, failed to record it: $e.")
        }
    }

    /**
     * Attaches a snapshot of the buffered steps to [properties] under [STEPS_KEY],
     * unless the caller already provided that key or the buffer is empty. The buffer
     * is left intact for subsequent exceptions.
     */
    fun attachTo(properties: MutableMap<String, Any>) {
        if (properties.containsKey(STEPS_KEY)) {
            return
        }
        val snapshot = snapshot()
        if (snapshot.isNotEmpty()) {
            properties[STEPS_KEY] = snapshot
        }
    }

    /** Returns a snapshot of the buffered steps, oldest first. */
    fun snapshot(): List<Map<String, Any>> =
        synchronized(lock) {
            steps.map { it.step }
        }

    fun clear() {
        synchronized(lock) {
            steps.clear()
            totalBytes = 0
        }
    }

    internal companion object {
        const val MESSAGE_KEY: String = "\$message"
        const val TIMESTAMP_KEY: String = "\$timestamp"
        const val STEPS_KEY: String = "\$exception_steps"

        private val mapType = object : TypeToken<Map<String, Any>>() {}.type
    }
}

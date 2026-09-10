package com.posthog.internal

import java.util.Date

/**
 * A call the host app made before `setup()` enabled the SDK.
 */
internal sealed class PostHogPreSetupCall {
    class Capture(
        val event: String,
        val distinctId: String?,
        val properties: Map<String, Any>?,
        val userProperties: Map<String, Any>?,
        val userPropertiesSetOnce: Map<String, Any>?,
        val groups: Map<String, String>?,
        val timestamp: Date,
    ) : PostHogPreSetupCall()

    class Identify(
        val distinctId: String,
        val userProperties: Map<String, Any>?,
        val userPropertiesSetOnce: Map<String, Any>?,
    ) : PostHogPreSetupCall()

    class Register(
        val key: String,
        val value: Any,
    ) : PostHogPreSetupCall()
}

/**
 * Holds `capture`, `identify` and `register` calls made before `setup()` so they can be replayed
 * once the SDK is enabled, instead of being dropped.
 *
 * A host that sets the SDK up off the main thread, or from a framework runtime that reaches
 * `setup()` late, still races app open, the first screen view and deep link attribution against
 * init. Those events are the reason the buffer exists, so once [maxSize] is reached the buffer
 * keeps what it already has and drops the newest call: the earliest calls of a startup are the
 * valuable ones, and an overflow means `setup()` was never reached at all.
 */
internal class PostHogPreSetupBuffer(private val maxSize: Int = MAX_SIZE) {
    private val lock = Any()
    private val calls = ArrayDeque<PostHogPreSetupCall>()
    private var dropped = 0

    fun add(call: PostHogPreSetupCall) {
        synchronized(lock) {
            if (calls.size >= maxSize) {
                dropped++
                return
            }
            calls.add(call)
        }
    }

    /**
     * Removes and returns every buffered call, oldest first, together with the number of calls
     * that overflowed [maxSize].
     */
    fun drain(): Pair<List<PostHogPreSetupCall>, Int> {
        synchronized(lock) {
            val drained = calls.toList()
            val droppedCount = dropped
            calls.clear()
            dropped = 0
            return drained to droppedCount
        }
    }

    fun clear() {
        synchronized(lock) {
            calls.clear()
            dropped = 0
        }
    }

    companion object {
        const val MAX_SIZE: Int = 1000
    }
}

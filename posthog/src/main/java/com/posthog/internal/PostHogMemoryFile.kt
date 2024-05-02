package com.posthog.internal

import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import java.io.InputStream
import java.io.OutputStream

internal class PostHogMemoryFile(private val event: PostHogEvent) : PostHogFileInterface {
    override fun outputStream(): OutputStream {
        // NoOp since its in-memory
        TODO("Not yet implemented")
    }

    override fun inputStream(): InputStream {
        // NoOp since its in-memory
        TODO("Not yet implemented")
    }

    override fun deleteSafely(config: PostHogConfig) {
        // NoOp since its in-memory
    }

    override fun name(): String {
        return event.uuid?.toString() ?: event.event
    }

    override fun isStreamable(): Boolean {
        // this is to avoid round trip serialization
        return false
    }

    override fun event(config: PostHogConfig): PostHogEvent {
        return event
    }
}

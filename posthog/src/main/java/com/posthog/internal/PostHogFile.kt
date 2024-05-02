package com.posthog.internal

import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream

internal class PostHogFile(private val file: File) : PostHogFileInterface {
    @Throws(FileNotFoundException::class, SecurityException::class)
    override fun outputStream(): OutputStream {
        return file.outputStream()
    }

    @Throws(FileNotFoundException::class, SecurityException::class)
    override fun inputStream(): InputStream {
        return file.inputStream()
    }

    override fun deleteSafely(config: PostHogConfig) {
        file.deleteSafely(config)
    }

    override fun name(): String {
        return file.name
    }

    override fun isStreamable(): Boolean {
        return true
    }

    override fun event(config: PostHogConfig): PostHogEvent {
        val inputStream = config.encryption?.decrypt(inputStream()) ?: inputStream()
        inputStream.use {
            return config.serializer.deserialize<PostHogEvent>(it.reader().buffered())
        }
    }
}

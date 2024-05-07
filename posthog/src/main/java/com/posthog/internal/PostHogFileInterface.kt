package com.posthog.internal

import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import java.io.InputStream
import java.io.OutputStream

internal interface PostHogFileInterface {
    fun outputStream(): OutputStream

    fun inputStream(): InputStream

    fun deleteSafely(config: PostHogConfig)

    fun name(): String

    fun isStreamable(): Boolean

    fun event(config: PostHogConfig): PostHogEvent
}

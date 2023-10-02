package com.posthog.internal

import com.posthog.PostHogConfig
import com.posthog.apiKey
import kotlin.test.Test

internal class PostHogQueueTest {
    private fun getSut(
        host: String,
    ): PostHogQueue {
        val config = PostHogConfig(apiKey, host)
        val serializer = PostHogSerializer(config)
        val api = PostHogApi(config, serializer)
        return PostHogQueue(config, api, serializer)
    }

    @Test
    fun `deserializes json to date`() {
    }
}

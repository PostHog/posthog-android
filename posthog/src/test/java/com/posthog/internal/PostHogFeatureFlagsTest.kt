package com.posthog.internal

import com.posthog.PostHogConfig
import com.posthog.apiKey
import com.posthog.mockHttp
import com.posthog.responseApi
import com.posthog.shutdownAndAwaitTermination
import okhttp3.mockwebserver.MockResponse
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PostHogFeatureFlagsTest {
    private val executor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("Test"))

    private fun getSut(
        host: String,
        networkStatus: PostHogNetworkStatus? = null,
    ): PostHogFeatureFlags {
        val config = PostHogConfig(apiKey, host).apply {
            this.networkStatus = networkStatus
        }
        val serializer = PostHogSerializer(config)
        val dateProvider = PostHogCalendarDateProvider()
        val api = PostHogApi(config, serializer, dateProvider)
        return PostHogFeatureFlags(config, api, executor = executor)
    }

    @Test
    fun `load flags bails out if not connected`() {
        val http = mockHttp(
            response =
            MockResponse()
                .setBody(responseApi),
        )
        val url = http.url("/")

        val sut = getSut(host = url.toString(), networkStatus = {
            false
        })

        sut.loadFeatureFlags("distinctId", "anonId", emptyMap(), null)

        executor.shutdownAndAwaitTermination()

        assertNull(sut.getFeatureFlags())
    }

    @Test
    fun `load flags from decide api and call the onFeatureFlags callback`() {
        val http = mockHttp(
            response =
            MockResponse()
                .setBody(responseApi),
        )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        var callback = false
        sut.loadFeatureFlags("my_identify", "anonId", emptyMap()) {
            callback = true
        }

        executor.shutdownAndAwaitTermination()

        assertTrue(sut.getFeatureFlag("4535-funnel-bar-viz", defaultValue = false) as Boolean)
        assertTrue(sut.getFeatureFlagPayload("thePayload", defaultValue = false) as Boolean)
        assertTrue(callback)
    }

    @Test
    fun `clear the loaded feature flags`() {
        val http = mockHttp(
            response =
            MockResponse()
                .setBody(responseApi),
        )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        sut.loadFeatureFlags("my_identify", "anonId", emptyMap(), null)

        executor.shutdownAndAwaitTermination()

        assertTrue(sut.getFeatureFlag("4535-funnel-bar-viz", defaultValue = false) as Boolean)

        sut.clear()

        assertNull(sut.getFeatureFlags())
    }

    @Test
    fun `returns all feature flags`() {
        val http = mockHttp(
            response =
            MockResponse()
                .setBody(responseApi),
        )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        sut.loadFeatureFlags("my_identify", "anonId", emptyMap(), null)

        executor.shutdownAndAwaitTermination()

        val flags = sut.getFeatureFlags()
        assertEquals(1, flags!!.size)
    }

    @Test
    fun `returns default value if given`() {
        val http = mockHttp(
            response =
            MockResponse()
                .setBody(responseApi),
        )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        sut.loadFeatureFlags("my_identify", "anonId", emptyMap(), null)

        executor.shutdownAndAwaitTermination()

        assertTrue(sut.getFeatureFlag("notFound", defaultValue = true) as Boolean)
    }

    @Test
    fun `merge feature flags if no errors`() {
        val http = mockHttp(
            response =
            MockResponse()
                .setBody(responseApi),
        )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        sut.loadFeatureFlags("my_identify", "anonId", emptyMap(), null)

        // do not use extension to not shutdown the executor
        executor.submit {}.get()

        val currentFlag = """"4535-funnel-bar-viz": true"""
        val newFlag =
            """$currentFlag,
            |"foo": true
            """.trimMargin()
        val newResponse = responseApi.replace(currentFlag, newFlag)
        val response = MockResponse()
            .setBody(newResponse)
        http.enqueue(response)

        sut.loadFeatureFlags("my_identify", "anonId", emptyMap(), null)

        // do not use extension to not shutdown the executor
        executor.submit {}.get()

        executor.shutdownAndAwaitTermination()

        assertTrue(sut.getFeatureFlag("4535-funnel-bar-viz", defaultValue = false) as Boolean)
        assertTrue(sut.getFeatureFlag("foo", defaultValue = false) as Boolean)
    }
}

package com.posthog.internal

import com.posthog.PostHogConfig
import com.posthog.apiKey
import com.posthog.awaitExecution
import com.posthog.mockHttp
import com.posthog.shutdownAndAwaitTermination
import okhttp3.mockwebserver.MockResponse
import java.io.File
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PostHogFeatureFlagsTest {
    private val executor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("Test"))

    private val file = File("src/test/resources/json/basic-decide-no-errors.json")
    private val responseDecideApi = file.readText()

    private fun getSut(
        host: String,
        networkStatus: PostHogNetworkStatus? = null,
    ): PostHogFeatureFlags {
        val config = PostHogConfig(apiKey, host).apply {
            this.networkStatus = networkStatus
        }
        val dateProvider = PostHogCalendarDateProvider()
        val api = PostHogApi(config, dateProvider)
        return PostHogFeatureFlags(config, api, executor = executor)
    }

    @Test
    fun `load flags bails out if not connected`() {
        val http = mockHttp(
            response =
            MockResponse()
                .setBody(responseDecideApi),
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
                .setBody(responseDecideApi),
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
                .setBody(responseDecideApi),
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
                .setBody(responseDecideApi),
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
                .setBody(responseDecideApi),
        )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        sut.loadFeatureFlags("my_identify", "anonId", emptyMap(), null)

        executor.shutdownAndAwaitTermination()

        assertTrue(sut.getFeatureFlag("notFound", defaultValue = true) as Boolean)
    }

    @Test
    fun `merge feature flags if there are errors`() {
        val http = mockHttp(
            response =
            MockResponse()
                .setBody(responseDecideApi),
        )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        sut.loadFeatureFlags("my_identify", "anonId", emptyMap(), null)

        executor.awaitExecution()

        val file = File("src/test/resources/json/basic-decide-with-errors.json")

        val response = MockResponse()
            .setBody(file.readText())
        http.enqueue(response)

        sut.loadFeatureFlags("my_identify", "anonId", emptyMap(), null)

        executor.shutdownAndAwaitTermination()

        assertTrue(sut.getFeatureFlag("4535-funnel-bar-viz", defaultValue = false) as Boolean)
        assertTrue(sut.getFeatureFlag("foo", defaultValue = false) as Boolean)

        assertTrue(sut.getFeatureFlagPayload("thePayload", defaultValue = false) as Boolean)
        assertTrue(sut.getFeatureFlagPayload("foo", defaultValue = false) as Boolean)
    }

    @Test
    fun `returns flag enabled if multivariant`() {
        val file = File("src/test/resources/json/basic-decide-with-non-active-flags.json")

        val http = mockHttp(
            response =
            MockResponse()
                .setBody(file.readText()),
        )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        sut.loadFeatureFlags("my_identify", "anonId", emptyMap(), null)

        executor.shutdownAndAwaitTermination()

        assertTrue(sut.isFeatureEnabled("4535-funnel-bar-viz", defaultValue = false))
        assertFalse(sut.isFeatureEnabled("IAmInactive", defaultValue = true))
        assertTrue(sut.isFeatureEnabled("splashScreenName", defaultValue = false))
        assertTrue(sut.isFeatureEnabled("IDontExist", defaultValue = true))
    }

    @Test
    fun `getFeatureFlagPayload returns non strigified JSON`() {
        val file = File("src/test/resources/json/decide-with-stringfied-flags.json")

        val http = mockHttp(
            response =
            MockResponse()
                .setBody(file.readText()),
        )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        sut.loadFeatureFlags("my_identify", "anonId", emptyMap(), null)

        executor.shutdownAndAwaitTermination()

        assertEquals("theString", sut.getFeatureFlagPayload("theString", defaultValue = null) as String)
        assertEquals(123, sut.getFeatureFlagPayload("theInteger", defaultValue = null) as Int)
        assertEquals(123.5, sut.getFeatureFlagPayload("theDouble", defaultValue = null) as Double)

        val theObject = mapOf<String, Any>("key" to "value")
        @Suppress("UNCHECKED_CAST")
        assertEquals(theObject, sut.getFeatureFlagPayload("theObject", defaultValue = null) as Map<String, Any>)

        val theArray = listOf(1, "2", 3.5)
        @Suppress("UNCHECKED_CAST")
        assertEquals(theArray, sut.getFeatureFlagPayload("theArray", defaultValue = null) as List<Any>)

        assertTrue(sut.getFeatureFlagPayload("theBoolean", defaultValue = null) as Boolean)
        assertNull(sut.getFeatureFlagPayload("theNull", defaultValue = null))
        assertEquals("[1, 2", sut.getFeatureFlagPayload("theBroken", defaultValue = null) as String)
    }
}

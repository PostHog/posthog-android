package com.posthog.internal

import com.posthog.API_KEY
import com.posthog.PostHogConfig
import com.posthog.awaitExecution
import com.posthog.internal.PostHogPreferences.Companion.FEATURE_FLAGS
import com.posthog.internal.PostHogPreferences.Companion.FEATURE_FLAGS_PAYLOAD
import com.posthog.internal.PostHogPreferences.Companion.SESSION_REPLAY
import com.posthog.mockHttp
import com.posthog.shutdownAndAwaitTermination
import okhttp3.mockwebserver.MockResponse
import java.io.File
import java.util.concurrent.Executors
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PostHogFeatureFlagsTest {
    private val executor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("Test"))

    private val file = File("src/test/resources/json/basic-decide-no-errors.json")
    private val responseDecideApi = file.readText()
    private val preferences = PostHogMemoryPreferences()

    private var config: PostHogConfig? = null

    private fun getSut(
        host: String,
        networkStatus: PostHogNetworkStatus? = null,
    ): PostHogFeatureFlags {
        config =
            PostHogConfig(API_KEY, host).apply {
                this.networkStatus = networkStatus
                cachePreferences = preferences
            }
        val api = PostHogApi(config!!)
        return PostHogFeatureFlags(config!!, api, executor = executor)
    }

    @BeforeTest
    fun `set up`() {
        preferences.clear()
    }

    @Test
    fun `load flags bails out if not connected`() {
        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseDecideApi),
            )
        val url = http.url("/")

        val sut =
            getSut(host = url.toString(), networkStatus = {
                false
            })

        sut.loadFeatureFlags("distinctId", anonymousId = "anonId", emptyMap(), null)

        executor.shutdownAndAwaitTermination()

        assertNull(sut.getFeatureFlags())
    }

    @Test
    fun `load flags from decide api and call the onFeatureFlags callback`() {
        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseDecideApi),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        var callback = false
        sut.loadFeatureFlags("my_identify", anonymousId = "anonId", emptyMap()) {
            callback = true
        }

        executor.shutdownAndAwaitTermination()

        assertTrue(sut.getFeatureFlag("4535-funnel-bar-viz", defaultValue = false) as Boolean)
        assertTrue(sut.getFeatureFlagPayload("thePayload", defaultValue = false) as Boolean)
        assertFalse(sut.isSessionReplayFlagActive())
        assertTrue(callback)
    }

    @Test
    fun `clear the loaded feature flags`() {
        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseDecideApi),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        sut.loadFeatureFlags("my_identify", anonymousId = "anonId", emptyMap(), null)

        executor.shutdownAndAwaitTermination()

        assertTrue(sut.getFeatureFlag("4535-funnel-bar-viz", defaultValue = false) as Boolean)
        assertNotNull(preferences.getValue(FEATURE_FLAGS))
        assertNotNull(preferences.getValue(FEATURE_FLAGS_PAYLOAD))

        sut.clear()

        assertNull(sut.getFeatureFlags())
        assertNull(preferences.getValue(FEATURE_FLAGS))
        assertNull(preferences.getValue(FEATURE_FLAGS_PAYLOAD))
    }

    @Test
    fun `returns all feature flags`() {
        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseDecideApi),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        sut.loadFeatureFlags("my_identify", anonymousId = "anonId", emptyMap(), null)

        executor.shutdownAndAwaitTermination()

        val flags = sut.getFeatureFlags()
        assertEquals(1, flags!!.size)
    }

    @Test
    fun `returns default value if given`() {
        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseDecideApi),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        sut.loadFeatureFlags("my_identify", anonymousId = "anonId", emptyMap(), null)

        executor.shutdownAndAwaitTermination()

        assertTrue(sut.getFeatureFlag("notFound", defaultValue = true) as Boolean)
    }

    @Test
    fun `merge feature flags if there are errors`() {
        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseDecideApi),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        sut.loadFeatureFlags("my_identify", anonymousId = "anonId", emptyMap(), null)

        executor.awaitExecution()

        val file = File("src/test/resources/json/basic-decide-with-errors.json")

        val response =
            MockResponse()
                .setBody(file.readText())
        http.enqueue(response)

        sut.loadFeatureFlags("my_identify", anonymousId = "anonId", emptyMap(), null)

        executor.shutdownAndAwaitTermination()

        assertTrue(sut.getFeatureFlag("4535-funnel-bar-viz", defaultValue = false) as Boolean)
        assertTrue(sut.getFeatureFlag("foo", defaultValue = false) as Boolean)

        assertTrue(sut.getFeatureFlagPayload("thePayload", defaultValue = false) as Boolean)
        assertTrue(sut.getFeatureFlagPayload("foo", defaultValue = false) as Boolean)
    }

    @Test
    fun `returns flag enabled if multivariant`() {
        val file = File("src/test/resources/json/basic-decide-with-non-active-flags.json")

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(file.readText()),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        sut.loadFeatureFlags("my_identify", anonymousId = "anonId", emptyMap(), null)

        executor.shutdownAndAwaitTermination()

        assertTrue(sut.isFeatureEnabled("4535-funnel-bar-viz", defaultValue = false))
        assertFalse(sut.isFeatureEnabled("IAmInactive", defaultValue = true))
        assertTrue(sut.isFeatureEnabled("splashScreenName", defaultValue = false))
        assertTrue(sut.isFeatureEnabled("IDontExist", defaultValue = true))
    }

    @Test
    fun `getFeatureFlagPayload returns non strigified JSON`() {
        val file = File("src/test/resources/json/decide-with-stringfied-flags.json")

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(file.readText()),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        sut.loadFeatureFlags("my_identify", anonymousId = "anonId", emptyMap(), null)

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

    @Test
    fun `cache feature flags after loading from the network`() {
        // preload items
        preferences.setValue(FEATURE_FLAGS, mapOf("foo" to true))
        preferences.setValue(FEATURE_FLAGS_PAYLOAD, mapOf("foo" to true))

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(file.readText()),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        assertTrue(sut.isFeatureEnabled("foo", defaultValue = false))
        assertTrue(sut.getFeatureFlagPayload("foo", defaultValue = false) as Boolean)
    }

    @Test
    fun `load feature flags from cache if not loaded from the network yet`() {
        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseDecideApi),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        sut.loadFeatureFlags("my_identify", anonymousId = "anonId", emptyMap(), null)

        executor.shutdownAndAwaitTermination()

        @Suppress("UNCHECKED_CAST")
        val flags = preferences.getValue(FEATURE_FLAGS) as? Map<String, Any>

        @Suppress("UNCHECKED_CAST")
        val payloads = preferences.getValue(FEATURE_FLAGS_PAYLOAD) as? Map<String, Any?>

        assertTrue(flags?.get("4535-funnel-bar-viz") as Boolean)
        assertTrue(payloads?.get("thePayload") as Boolean)
    }

    @Test
    fun `returns session replay enabled after decide API call`() {
        val file = File("src/test/resources/json/basic-decide-recording.json")

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(file.readText()),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        sut.loadFeatureFlags("my_identify", anonymousId = "anonId", emptyMap(), null)

        executor.shutdownAndAwaitTermination()

        assertTrue(sut.isSessionReplayFlagActive())
        assertEquals("/b/", config?.snapshotEndpoint)

        sut.clear()

        assertFalse(sut.isSessionReplayFlagActive())
    }

    @Test
    fun `read session replay config from start`() {
        val flags = mapOf("endpoint" to "/b/")
        preferences.setValue(SESSION_REPLAY, flags)

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseDecideApi),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        assertTrue(sut.isSessionReplayFlagActive())
        assertEquals("/b/", config?.snapshotEndpoint)
    }

    @Test
    fun `returns isSessionReplayFlagActive true if bool linked flag is enabled`() {
        val file = File("src/test/resources/json/basic-decide-recording-bool-linked-enabled.json")

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(file.readText()),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        sut.loadFeatureFlags("my_identify", anonymousId = "anonId", emptyMap(), null)

        executor.shutdownAndAwaitTermination()

        assertTrue(sut.isSessionReplayFlagActive())

        sut.clear()
    }

    @Test
    fun `returns isSessionReplayFlagActive false if bool linked flag is disabled`() {
        val file = File("src/test/resources/json/basic-decide-recording-bool-linked-disabled.json")

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(file.readText()),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        sut.loadFeatureFlags("my_identify", anonymousId = "anonId", emptyMap(), null)

        executor.shutdownAndAwaitTermination()

        assertFalse(sut.isSessionReplayFlagActive())

        sut.clear()
    }

    @Test
    fun `returns isSessionReplayFlagActive true if multi variant linked flag is a match`() {
        val file = File("src/test/resources/json/basic-decide-recording-bool-linked-variant-match.json")

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(file.readText()),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        sut.loadFeatureFlags("my_identify", anonymousId = "anonId", emptyMap(), null)

        executor.shutdownAndAwaitTermination()

        assertTrue(sut.isSessionReplayFlagActive())

        sut.clear()
    }

    @Test
    fun `returns isSessionReplayFlagActive false if multi variant linked flag is not a match`() {
        val file = File("src/test/resources/json/basic-decide-recording-bool-linked-variant-not-match.json")

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(file.readText()),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        sut.loadFeatureFlags("my_identify", anonymousId = "anonId", emptyMap(), null)

        executor.shutdownAndAwaitTermination()

        assertFalse(sut.isSessionReplayFlagActive())

        sut.clear()
    }
}

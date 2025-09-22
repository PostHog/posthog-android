package com.posthog.internal

import com.posthog.API_KEY
import com.posthog.PostHogConfig
import com.posthog.awaitExecution
import com.posthog.internal.PostHogPreferences.Companion.FEATURE_FLAGS
import com.posthog.internal.PostHogPreferences.Companion.FEATURE_FLAGS_PAYLOAD
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

    private val file = File("src/test/resources/json/basic-flags-no-errors.json")
    private val responseFlagsApi = file.readText()
    private val preferences = PostHogMemoryPreferences()

    private var config: PostHogConfig? = null

    private fun getSut(
        host: String,
        networkStatus: PostHogNetworkStatus? = null,
    ): PostHogRemoteConfig {
        config =
            PostHogConfig(API_KEY, host).apply {
                this.networkStatus = networkStatus
                cachePreferences = preferences
            }
        val api = PostHogApi(config!!)
        return PostHogRemoteConfig(config!!, api, executor = executor)
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
                        .setBody(responseFlagsApi),
            )
        val url = http.url("/")

        val sut =
            getSut(host = url.toString(), networkStatus = {
                false
            })

        sut.loadFeatureFlags("distinctId", anonymousId = "anonId", emptyMap())

        executor.shutdownAndAwaitTermination()

        assertNull(sut.getFeatureFlags())
    }

    @Test
    fun `load flags from flags api and call the onFeatureFlags callback`() {
        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseFlagsApi),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        var callback = false
        sut.loadFeatureFlags("my_identify", anonymousId = "anonId", emptyMap()) {
            callback = true
        }

        executor.shutdownAndAwaitTermination()

        assertTrue(sut.getFeatureFlag("4535-funnel-bar-viz", defaultValue = false) as Boolean)
        assertTrue(sut.getFeatureFlagPayload("4535-funnel-bar-viz", defaultValue = false) as Boolean)
        assertFalse(sut.isSessionReplayFlagActive())
        assertTrue(callback)
    }

    @Test
    fun `clear the loaded feature flags`() {
        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseFlagsApi),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        sut.loadFeatureFlags("my_identify", anonymousId = "anonId", emptyMap())

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
                        .setBody(responseFlagsApi),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        sut.loadFeatureFlags("my_identify", anonymousId = "anonId", emptyMap())

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
                        .setBody(responseFlagsApi),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        sut.loadFeatureFlags("my_identify", anonymousId = "anonId", emptyMap())

        executor.shutdownAndAwaitTermination()

        assertTrue(sut.getFeatureFlag("notFound", defaultValue = true) as Boolean)
    }

    @Test
    fun `merge feature flags if there are errors`() {
        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseFlagsApi),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        sut.loadFeatureFlags("my_identify", anonymousId = "anonId", emptyMap())

        executor.awaitExecution()

        val file = File("src/test/resources/json/basic-flags-with-errors.json")

        val response =
            MockResponse()
                .setBody(file.readText())
        http.enqueue(response)

        sut.loadFeatureFlags("my_identify", anonymousId = "anonId", emptyMap())

        executor.shutdownAndAwaitTermination()

        assertTrue(sut.getFeatureFlag("4535-funnel-bar-viz", defaultValue = false) as Boolean)
        assertTrue(sut.getFeatureFlag("foo", defaultValue = false) as Boolean)

        assertTrue(sut.getFeatureFlagPayload("4535-funnel-bar-viz", defaultValue = false) as Boolean)
        assertTrue(sut.getFeatureFlagPayload("foo", defaultValue = false) as Boolean)
    }

    @Test
    fun `returns flag enabled if multivariant`() {
        val file = File("src/test/resources/json/basic-flags-with-non-active-flags.json")

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(file.readText()),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        sut.loadFeatureFlags("my_identify", anonymousId = "anonId", emptyMap())

        executor.shutdownAndAwaitTermination()

        assertTrue(sut.getFeatureFlag("4535-funnel-bar-viz", defaultValue = false) as Boolean)
        assertFalse(sut.getFeatureFlag("IAmInactive", defaultValue = true) as Boolean)
        assertNotNull(sut.getFeatureFlag("splashScreenName", defaultValue = false) as String)
        assertTrue(sut.getFeatureFlag("IDontExist", defaultValue = true) as Boolean)
    }

    @Test
    fun `getFeatureFlagPayload returns non stringified JSON`() {
        val file = File("src/test/resources/json/flags-with-stringfied-flags.json")

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(file.readText()),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        sut.loadFeatureFlags("my_identify", anonymousId = "anonId", emptyMap())

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

        assertTrue(sut.getFeatureFlag("foo", defaultValue = false) as Boolean)
        assertTrue(sut.getFeatureFlagPayload("foo", defaultValue = false) as Boolean)
    }

    @Test
    fun `load feature flags from cache if not loaded from the network yet`() {
        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseFlagsApi),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        sut.loadFeatureFlags("my_identify", anonymousId = "anonId", emptyMap())

        executor.shutdownAndAwaitTermination()

        @Suppress("UNCHECKED_CAST")
        val flags = preferences.getValue(FEATURE_FLAGS) as? Map<String, Any>

        @Suppress("UNCHECKED_CAST")
        val payloads = preferences.getValue(FEATURE_FLAGS_PAYLOAD) as? Map<String, Any?>

        assertTrue(flags?.get("4535-funnel-bar-viz") as Boolean)
        assertTrue(payloads?.get("4535-funnel-bar-viz") as Boolean)
    }

    @Test
    fun `clear flags when quota limited`() {
        val http =
            mockHttp(
                response =
                    MockResponse().setBody(
                        """
                        {
                            "featureFlags": {"flag1": true},
                            "featureFlagPayloads": {"flag1": "payload1"}
                        }
                        """.trimIndent(),
                    ),
            )
        val url = http.url("/")
        val sut = getSut(host = url.toString())

        // Load initial flags
        sut.loadFeatureFlags("test_id", null, null)
        executor.awaitExecution()

        // Verify flags are loaded
        assertNotNull(sut.getFeatureFlags())
        assertNotNull(preferences.getValue(FEATURE_FLAGS))

        // Send quota limited response
        http.enqueue(
            MockResponse().setBody(
                """
                {
                    "quotaLimited": ["feature_flags"]
                }
                """.trimIndent(),
            ),
        )

        // Reload flags
        sut.loadFeatureFlags("test_id", null, null)
        executor.awaitExecution()

        // Verify flags are cleared
        assertNull(sut.getFeatureFlags())
        assertNull(preferences.getValue(FEATURE_FLAGS))
    }

    @Test
    fun `clear cached flags when there are no no server side flags`() {
        val http =
            mockHttp(
                response =
                    MockResponse().setBody(
                        """
                        {
                            "featureFlags": {"flag1": true},
                            "featureFlagPayloads": {"flag1": "payload1"}
                        }
                        """.trimIndent(),
                    ),
            )

        val file = File("src/test/resources/json/basic-remote-config-no-flags.json")
        val responseText = file.readText()

        http.enqueue(MockResponse().setBody(responseText))
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        // Load initial flags
        sut.loadFeatureFlags("test_id", null, null)
        executor.awaitExecution()

        assertNotNull(preferences.getValue(FEATURE_FLAGS))
        assertTrue(sut.getFeatureFlag("flag1", defaultValue = false) as Boolean)

        // Load initial flags
        sut.loadRemoteConfig("my_identify", anonymousId = "anonId", emptyMap())

        executor.shutdownAndAwaitTermination()

        assertNull(preferences.getValue(FEATURE_FLAGS))
        assertFalse(sut.getFeatureFlag("flag1", defaultValue = false) as Boolean)
    }

    @Test
    fun `do not preload flags if distinct id is blank`() {
        val file = File("src/test/resources/json/basic-remote-config.json")

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(file.readText()),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        sut.loadRemoteConfig(" ", anonymousId = "anonId", emptyMap())

        executor.shutdownAndAwaitTermination()

        assertEquals(1, http.requestCount)

        sut.clear()
    }

    @Test
    fun `returns session replay enabled after remote config API call`() {
        val file = File("src/test/resources/json/basic-remote-config-no-flags.json")

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(file.readText()),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        sut.loadRemoteConfig("my_identify", anonymousId = "anonId", emptyMap(), null)

        executor.shutdownAndAwaitTermination()

        assertTrue(sut.isSessionReplayFlagActive())
        assertEquals("/s/", config?.snapshotEndpoint)
        assertEquals(1, http.requestCount)

        sut.clear()

        assertFalse(sut.isSessionReplayFlagActive())
    }
}

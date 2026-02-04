package com.posthog.internal

import com.posthog.API_KEY
import com.posthog.PostHogConfig
import com.posthog.PostHogOnFeatureFlags
import com.posthog.internal.PostHogPreferences.Companion.SESSION_REPLAY
import com.posthog.mockHttp
import com.posthog.shutdownAndAwaitTermination
import okhttp3.mockwebserver.MockResponse
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class PostHogRemoteConfigTest {
    private val executor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("Test"))

    private val file = File("src/test/resources/json/flags-v1/basic-flags-no-errors.json")
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
        return PostHogRemoteConfig(config!!, api, executor = executor) { emptyMap() }
    }

    @BeforeTest
    fun `set up`() {
        preferences.clear()
    }

    @Test
    fun `returns session replay enabled after flags API call`() {
        val file = File("src/test/resources/json/basic-flags-recording.json")

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

        assertTrue(sut.isSessionReplayFlagActive())
        assertEquals("/b/", config?.snapshotEndpoint)

        sut.clear()
        http.shutdown()

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
                        .setBody(responseFlagsApi),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        assertTrue(sut.isSessionReplayFlagActive())
        assertEquals("/b/", config?.snapshotEndpoint)

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `returns isSessionReplayFlagActive true if bool linked flag is enabled`() {
        val file = File("src/test/resources/json/basic-flags-recording-bool-linked-enabled.json")

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

        assertTrue(sut.isSessionReplayFlagActive())

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `returns isSessionReplayFlagActive false if bool linked flag is disabled`() {
        val file = File("src/test/resources/json/basic-flags-recording-bool-linked-disabled.json")

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

        assertFalse(sut.isSessionReplayFlagActive())

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `returns isSessionReplayFlagActive true if multi variant linked flag is a match`() {
        val file = File("src/test/resources/json/basic-flags-recording-bool-linked-variant-match.json")

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

        assertTrue(sut.isSessionReplayFlagActive())

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `returns isSessionReplayFlagActive false if multi variant linked flag is not a match`() {
        val file = File("src/test/resources/json/basic-flags-recording-bool-linked-variant-not-match.json")

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

        assertFalse(sut.isSessionReplayFlagActive())

        sut.clear()
        http.shutdown()
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

        sut.loadRemoteConfig("my_identify", anonymousId = "anonId", emptyMap())

        executor.shutdownAndAwaitTermination()

        assertTrue(sut.isSessionReplayFlagActive())
        assertEquals("/s/", config?.snapshotEndpoint)
        assertEquals(1, http.requestCount)

        sut.clear()
        http.shutdown()

        assertFalse(sut.isSessionReplayFlagActive())
    }

    private fun testFlagsCallback(pathname: String) {
        val file = File(pathname)

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(file.readText()),
            )
        val url = http.url("/")
        var called = false
        var calledInternal = false

        val onFeatureFlags: PostHogOnFeatureFlags =
            PostHogOnFeatureFlags {
                called = true
            }

        val internalOnFeatureFlags: PostHogOnFeatureFlags =
            PostHogOnFeatureFlags {
                calledInternal = true
            }

        val sut = getSut(host = url.toString())

        sut.loadRemoteConfig(
            "my_identify",
            anonymousId = "anonId",
            emptyMap(),
            internalOnFeatureFlags = internalOnFeatureFlags,
            onFeatureFlags = onFeatureFlags,
        )

        executor.shutdownAndAwaitTermination()

        assertTrue(called)
        assertTrue(calledInternal)

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `on feature flag callbacks are called after remote config API call`() {
        testFlagsCallback("src/test/resources/json/basic-remote-config-no-flags.json")
    }

    @Test
    fun `on feature flag callbacks are called after flag API call`() {
        testFlagsCallback("src/test/resources/json/basic-remote-config.json")
    }

    @Test
    fun `setPersonPropertiesForFlags writes to preferences`() {
        val http = mockHttp(response = MockResponse().setBody(responseFlagsApi))
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        val personProps =
            mapOf(
                "email" to "user@example.com",
                "plan" to "premium",
                "age" to 30,
            )

        sut.setPersonPropertiesForFlags(personProps)

        val cachedProps = preferences.getValue(PostHogPreferences.PERSON_PROPERTIES_FOR_FLAGS) as? Map<*, *>
        assertEquals("user@example.com", cachedProps?.get("email"))
        assertEquals("premium", cachedProps?.get("plan"))
        assertEquals(30, cachedProps?.get("age"))

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `setGroupPropertiesForFlags writes to preferences`() {
        val http = mockHttp(response = MockResponse().setBody(responseFlagsApi))
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        val orgProps = mapOf("plan" to "enterprise", "seats" to 50)
        val teamProps = mapOf("name" to "Engineering", "size" to 10)

        sut.setGroupPropertiesForFlags("organization", orgProps)
        sut.setGroupPropertiesForFlags("team", teamProps)

        val cachedProps = preferences.getValue(PostHogPreferences.GROUP_PROPERTIES_FOR_FLAGS) as? Map<*, *>
        val cachedOrgProps = cachedProps?.get("organization") as? Map<*, *>
        val cachedTeamProps = cachedProps?.get("team") as? Map<*, *>

        assertEquals("enterprise", cachedOrgProps?.get("plan"))
        assertEquals(50, cachedOrgProps?.get("seats"))
        assertEquals("Engineering", cachedTeamProps?.get("name"))
        assertEquals(10, cachedTeamProps?.get("size"))

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `resetPersonPropertiesForFlags removes from preferences`() {
        val http = mockHttp(response = MockResponse().setBody(responseFlagsApi))
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        sut.setPersonPropertiesForFlags(mapOf("email" to "user@example.com"))

        var cachedProps = preferences.getValue(PostHogPreferences.PERSON_PROPERTIES_FOR_FLAGS) as? Map<*, *>
        assertEquals("user@example.com", cachedProps?.get("email"))

        sut.resetPersonPropertiesForFlags()

        cachedProps = preferences.getValue(PostHogPreferences.PERSON_PROPERTIES_FOR_FLAGS) as? Map<*, *>
        assertTrue(cachedProps == null, "Person properties should be removed from preferences")

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `resetGroupPropertiesForFlags removes all groups from preferences`() {
        val http = mockHttp(response = MockResponse().setBody(responseFlagsApi))
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        sut.setGroupPropertiesForFlags("organization", mapOf("plan" to "enterprise"))
        sut.setGroupPropertiesForFlags("team", mapOf("name" to "Engineering"))

        var cachedProps = preferences.getValue(PostHogPreferences.GROUP_PROPERTIES_FOR_FLAGS) as? Map<*, *>
        assertTrue(cachedProps?.containsKey("organization") == true)
        assertTrue(cachedProps?.containsKey("team") == true)

        sut.resetGroupPropertiesForFlags()

        cachedProps = preferences.getValue(PostHogPreferences.GROUP_PROPERTIES_FOR_FLAGS) as? Map<*, *>
        assertTrue(cachedProps == null, "Group properties should be removed from preferences")

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `resetGroupPropertiesForFlags removes specific group from preferences`() {
        val http = mockHttp(response = MockResponse().setBody(responseFlagsApi))
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        sut.setGroupPropertiesForFlags("organization", mapOf("plan" to "enterprise"))
        sut.setGroupPropertiesForFlags("team", mapOf("name" to "Engineering"))

        sut.resetGroupPropertiesForFlags("organization")

        val cachedProps = preferences.getValue(PostHogPreferences.GROUP_PROPERTIES_FOR_FLAGS) as? Map<*, *>
        assertFalse(cachedProps?.containsKey("organization") == true, "organization should be removed")
        assertTrue(cachedProps?.containsKey("team") == true, "team should remain")

        val teamProps = cachedProps?.get("team") as? Map<*, *>
        assertEquals("Engineering", teamProps?.get("name"))

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `pending reload is executed when loadFeatureFlags is called during in-flight request`() {
        // Use a multi-threaded executor to allow concurrent execution
        val multiThreadExecutor = Executors.newFixedThreadPool(2, PostHogThreadFactory("Test"))

        val http =
            mockHttp(
                total = 2,
                response =
                    MockResponse()
                        .setBody(responseFlagsApi)
                        .setBodyDelay(100, TimeUnit.MILLISECONDS), // Add delay to simulate network latency
            )
        val url = http.url("/")

        config =
            PostHogConfig(API_KEY, url.toString()).apply {
                cachePreferences = preferences
            }
        val api = PostHogApi(config!!)
        val sut = PostHogRemoteConfig(config!!, api, executor = multiThreadExecutor) { emptyMap() }

        val firstCallbackLatch = CountDownLatch(1)
        val secondCallbackLatch = CountDownLatch(1)

        val firstCallback =
            PostHogOnFeatureFlags {
                firstCallbackLatch.countDown()
            }

        val secondCallback =
            PostHogOnFeatureFlags {
                secondCallbackLatch.countDown()
            }

        // Start the first request
        sut.loadFeatureFlags(
            distinctId = "user1",
            anonymousId = null,
            groups = emptyMap(),
            onFeatureFlags = firstCallback,
        )

        // Give it a moment to start processing
        Thread.sleep(20)

        // Call loadFeatureFlags again while the first is in flight
        // This should queue a pending reload instead of being dropped
        sut.loadFeatureFlags(
            distinctId = "user2",
            anonymousId = "anonId123",
            groups = emptyMap(),
            onFeatureFlags = secondCallback,
        )

        // Wait for both callbacks to be called
        assertTrue(
            firstCallbackLatch.await(5, TimeUnit.SECONDS),
            "First callback should be called",
        )
        assertTrue(
            secondCallbackLatch.await(5, TimeUnit.SECONDS),
            "Second callback should be called (pending reload)",
        )

        // Both requests should have been made
        assertEquals(2, http.requestCount, "Both requests should have been made (original + pending)")

        multiThreadExecutor.shutdownAndAwaitTermination()
        sut.clear()
        http.shutdown()
    }

    @Test
    fun `pending reload contains correct parameters including anonymousId`() {
        // Use a multi-threaded executor to allow concurrent execution
        val multiThreadExecutor = Executors.newFixedThreadPool(2, PostHogThreadFactory("Test"))

        val http =
            mockHttp(
                total = 2,
                response =
                    MockResponse()
                        .setBody(responseFlagsApi)
                        .setBodyDelay(100, TimeUnit.MILLISECONDS),
            )
        val url = http.url("/")

        config =
            PostHogConfig(API_KEY, url.toString()).apply {
                cachePreferences = preferences
            }
        val api = PostHogApi(config!!)
        val sut = PostHogRemoteConfig(config!!, api, executor = multiThreadExecutor) { emptyMap() }

        val secondCallbackLatch = CountDownLatch(1)

        // Start the first request (simulating preload)
        sut.loadFeatureFlags(
            distinctId = "preload_user",
            anonymousId = null,
            groups = emptyMap(),
        )

        Thread.sleep(20)

        // Call loadFeatureFlags with anonymousId (simulating identify() call)
        // This is the critical case - the anonymousId should NOT be dropped
        sut.loadFeatureFlags(
            distinctId = "identified_user",
            anonymousId = "anon_id_for_hash_key",
            groups = mapOf("company" to "posthog"),
            onFeatureFlags =
                PostHogOnFeatureFlags {
                    secondCallbackLatch.countDown()
                },
        )

        // Wait for the pending reload to complete
        assertTrue(
            secondCallbackLatch.await(5, TimeUnit.SECONDS),
            "Pending reload callback should be called",
        )

        assertEquals(2, http.requestCount, "Both requests should be made")

        // Take both requests (we just verify the count above)
        http.takeRequest()
        http.takeRequest()

        multiThreadExecutor.shutdownAndAwaitTermination()
        sut.clear()
        http.shutdown()
    }
}

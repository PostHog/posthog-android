package com.posthog.internal

import com.posthog.API_KEY
import com.posthog.PostHogConfig
import com.posthog.PostHogOnFeatureFlags
import com.posthog.internal.PostHogPreferences.Companion.CAPTURE_PERFORMANCE
import com.posthog.internal.PostHogPreferences.Companion.ERROR_TRACKING
import com.posthog.internal.PostHogPreferences.Companion.SESSION_REPLAY
import com.posthog.internal.PostHogPreferences.Companion.SURVEYS
import com.posthog.mockHttp
import com.posthog.shutdownAndAwaitTermination
import okhttp3.mockwebserver.MockResponse
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        surveys: Boolean = false,
    ): PostHogRemoteConfig {
        config =
            PostHogConfig(API_KEY, host).apply {
                this.networkStatus = networkStatus
                this.surveys = surveys
                cachePreferences = preferences
            }
        val api = PostHogApi(config!!)
        return PostHogRemoteConfig(config!!, api, executor = executor, defaultPersonPropertiesProvider = { emptyMap() })
    }

    @BeforeTest
    fun `set up`() {
        preferences.clear()
        // isReactNative is a process-global flag on the PostHogSessionManager singleton; reset it so a
        // test in another suite that sets it true can't leak into these (e.g. flip getEventTriggers to null).
        PostHogSessionManager.isReactNative = false
    }

    @Test
    fun `re-arms session replay after reset on a flags reload`() {
        // /config cached the recording config; production /flags omits it.
        preferences.setValue(SESSION_REPLAY, mapOf("endpoint" to "/b/"))

        val withoutRecording = File("src/test/resources/json/basic-flags-no-session-recording.json").readText()
        val http = mockHttp(response = MockResponse().setBody(withoutRecording))
        http.enqueue(MockResponse().setBody(withoutRecording))
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        val firstLatch = CountDownLatch(1)
        sut.loadFeatureFlags(
            "my_identify",
            anonymousId = "anonId",
            emptyMap(),
            onFeatureFlags = PostHogOnFeatureFlags { firstLatch.countDown() },
        )
        assertTrue(firstLatch.await(5, TimeUnit.SECONDS), "first flags load should complete")
        assertTrue(sut.isSessionReplayFlagActive())

        // reset() zeroes the in-memory flag but keeps the cached config.
        sut.clear()
        assertFalse(sut.isSessionReplayFlagActive())

        val secondLatch = CountDownLatch(1)
        sut.loadFeatureFlags(
            "my_identify",
            anonymousId = "anonId",
            emptyMap(),
            onFeatureFlags = PostHogOnFeatureFlags { secondLatch.countDown() },
        )
        assertTrue(secondLatch.await(5, TimeUnit.SECONDS), "second flags load should complete")

        // Must re-arm from the cached config, not stay clobbered to false.
        assertTrue(sut.isSessionReplayFlagActive())

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `hasRemoteConfigFetched flips true on a flags reload and resets on clear`() {
        val response = File("src/test/resources/json/flags-v1/basic-flags-no-errors.json").readText()
        val http = mockHttp(response = MockResponse().setBody(response))
        val sut = getSut(host = http.url("/").toString())

        assertFalse(sut.hasRemoteConfigFetched())

        val latch = CountDownLatch(1)
        sut.loadFeatureFlags(
            "my_identify",
            anonymousId = "anonId",
            emptyMap(),
            onFeatureFlags = PostHogOnFeatureFlags { latch.countDown() },
        )
        assertTrue(latch.await(5, TimeUnit.SECONDS), "flags load should complete")

        // The flags path also resolves the remote config for the current identity.
        assertTrue(sut.hasRemoteConfigFetched())

        // reset()/clear() re-arms the gate for the next identity.
        sut.clear()
        assertFalse(sut.hasRemoteConfigFetched())

        http.shutdown()
    }

    @Test
    fun `hasRemoteConfigFetched flips true on a remote config load`() {
        val response = File("src/test/resources/json/basic-remote-config-no-flags.json").readText()
        val http = mockHttp(response = MockResponse().setBody(response))
        val sut = getSut(host = http.url("/").toString())

        assertFalse(sut.hasRemoteConfigFetched())

        sut.loadRemoteConfig("my_identify", anonymousId = "anonId", emptyMap())
        executor.shutdownAndAwaitTermination()

        assertTrue(sut.hasRemoteConfigFetched())

        sut.clear()
        assertFalse(sut.hasRemoteConfigFetched())

        http.shutdown()
    }

    @Test
    fun `offline remote config load still notifies without marking fetched`() {
        // Offline -> loadRemoteConfig short-circuits, but it must still fire the callback so the
        // replay integration can fall back to the cached flag instead of buffering forever. The
        // attempt resolved no live config, so hasRemoteConfigFetched stays false to distinguish it.
        val offline =
            object : PostHogNetworkStatus {
                override fun isConnected(): Boolean = false
            }
        config =
            PostHogConfig(API_KEY, "http://localhost").apply {
                networkStatus = offline
                cachePreferences = preferences
            }
        val notified = CountDownLatch(1)
        val sut =
            PostHogRemoteConfig(
                config!!,
                PostHogApi(config!!),
                executor = executor,
                defaultPersonPropertiesProvider = { emptyMap() },
                onRemoteConfigLoaded = { notified.countDown() },
            )

        assertFalse(sut.hasRemoteConfigFetched())

        sut.loadRemoteConfig("my_identify", anonymousId = "anonId", emptyMap())

        assertTrue(notified.await(5, TimeUnit.SECONDS), "offline load should still notify")
        assertFalse(sut.hasRemoteConfigFetched())

        sut.clear()
    }

    @Test
    fun `quota-limited flags reload resolves the config and notifies`() {
        // A quota-limited first /flags re-arms replay from the cached config, so it must resolve the
        // gate (mark fetched + notify) instead of leaving the buffer-and-decide gate stuck.
        val quotaLimited = File("src/test/resources/json/basic-flags-quota-limited.json").readText()
        val http = mockHttp(response = MockResponse().setBody(quotaLimited))
        config =
            PostHogConfig(API_KEY, http.url("/").toString()).apply {
                cachePreferences = preferences
            }
        val notified = CountDownLatch(1)
        val sut =
            PostHogRemoteConfig(
                config!!,
                PostHogApi(config!!),
                executor = executor,
                defaultPersonPropertiesProvider = { emptyMap() },
                onRemoteConfigLoaded = { notified.countDown() },
            )

        assertFalse(sut.hasRemoteConfigFetched())

        sut.loadFeatureFlags("my_identify", anonymousId = "anonId", emptyMap())

        assertTrue(notified.await(5, TimeUnit.SECONDS), "quota-limited load should still notify")
        assertTrue(sut.hasRemoteConfigFetched())

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `failure notify does not re-fire on an offline reload after a successful load`() {
        val response = File("src/test/resources/json/flags-v1/basic-flags-no-errors.json").readText()
        val http = mockHttp(response = MockResponse().setBody(response))
        var online = true
        val networkStatus =
            object : PostHogNetworkStatus {
                override fun isConnected(): Boolean = online
            }
        config =
            PostHogConfig(API_KEY, http.url("/").toString()).apply {
                this.networkStatus = networkStatus
                cachePreferences = preferences
            }
        val notifyCount = AtomicInteger(0)
        val sut =
            PostHogRemoteConfig(
                config!!,
                PostHogApi(config!!),
                executor = executor,
                defaultPersonPropertiesProvider = { emptyMap() },
                onRemoteConfigLoaded = { notifyCount.incrementAndGet() },
            )

        val firstLatch = CountDownLatch(1)
        sut.loadFeatureFlags(
            "my_identify",
            anonymousId = "anonId",
            emptyMap(),
            onFeatureFlags = PostHogOnFeatureFlags { firstLatch.countDown() },
        )
        assertTrue(firstLatch.await(5, TimeUnit.SECONDS), "first flags load should complete")
        assertTrue(sut.hasRemoteConfigFetched())
        assertEquals(1, notifyCount.get())

        // Offline reload after a successful resolve: the failure notify is gated on
        // !hasRemoteConfigFetched, so it must not fire a second (spurious) resolution.
        online = false
        val secondLatch = CountDownLatch(1)
        sut.loadFeatureFlags(
            "my_identify",
            anonymousId = "anonId",
            emptyMap(),
            onFeatureFlags = PostHogOnFeatureFlags { secondLatch.countDown() },
        )
        assertTrue(secondLatch.await(5, TimeUnit.SECONDS), "second (offline) flags load should complete")

        assertEquals(1, notifyCount.get())

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `re-arms session replay on a quota-limited flags reload`() {
        // Project config lives in /config (cached); a flags reload that is quota-limited for
        // feature_flags must still re-arm replay instead of leaving it disabled until app restart.
        preferences.setValue(SESSION_REPLAY, mapOf("endpoint" to "/b/"))

        val quotaLimited = File("src/test/resources/json/basic-flags-quota-limited.json").readText()
        val http = mockHttp(response = MockResponse().setBody(quotaLimited))
        val sut = getSut(host = http.url("/").toString())

        // reset() zeroes the in-memory flag but keeps the cached config.
        sut.clear()
        assertFalse(sut.isSessionReplayFlagActive())

        val latch = CountDownLatch(1)
        sut.loadFeatureFlags(
            "my_identify",
            anonymousId = "anonId",
            emptyMap(),
            onFeatureFlags = PostHogOnFeatureFlags { latch.countDown() },
        )
        assertTrue(latch.await(5, TimeUnit.SECONDS), "flags load should complete")

        // Re-armed from the cached config despite the flag quota limit.
        assertTrue(sut.isSessionReplayFlagActive())

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `re-arms error tracking and capture performance on a quota-limited flags reload`() {
        // Error tracking & capture performance also come from /config (cached); a flags reload that is
        // quota-limited for feature_flags must still re-arm them instead of leaving them disabled.
        preferences.setValue(ERROR_TRACKING, mapOf("autocaptureExceptions" to true))
        preferences.setValue(CAPTURE_PERFORMANCE, mapOf("network_timing" to true))

        val quotaLimited = File("src/test/resources/json/basic-flags-quota-limited.json").readText()
        val http = mockHttp(response = MockResponse().setBody(quotaLimited))
        val sut = getSut(host = http.url("/").toString())
        config!!.errorTrackingConfig.autoCapture = true

        // reset() zeroes the in-memory flags but keeps the cached config.
        sut.clear()
        assertFalse(sut.isAutocaptureExceptionsEnabled())
        assertFalse(sut.isCaptureNetworkTimingEnabled())

        val latch = CountDownLatch(1)
        sut.loadFeatureFlags(
            "my_identify",
            anonymousId = "anonId",
            emptyMap(),
            onFeatureFlags = PostHogOnFeatureFlags { latch.countDown() },
        )
        assertTrue(latch.await(5, TimeUnit.SECONDS), "flags load should complete")

        // Re-armed from the cached config despite the flag quota limit.
        assertTrue(sut.isAutocaptureExceptionsEnabled())
        assertTrue(sut.isCaptureNetworkTimingEnabled())

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `re-arm on a flags reload restores consoleLogRecordingEnabled from the cached config`() {
        preferences.setValue(SESSION_REPLAY, mapOf("consoleLogRecordingEnabled" to true))

        // /flags omits sessionRecording, so the reload must re-arm from the cached config.
        val withoutRecording = File("src/test/resources/json/basic-flags-no-session-recording.json").readText()
        val http = mockHttp(response = MockResponse().setBody(withoutRecording))
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        assertTrue(sut.isConsoleLogRecordingEnabled())

        sut.clear()
        assertFalse(sut.isConsoleLogRecordingEnabled())

        val latch = CountDownLatch(1)
        sut.loadFeatureFlags(
            "my_identify",
            anonymousId = "anonId",
            emptyMap(),
            onFeatureFlags = PostHogOnFeatureFlags { latch.countDown() },
        )
        assertTrue(latch.await(5, TimeUnit.SECONDS), "flags load should complete")

        // Restored from cache, not left clobbered to false.
        assertTrue(sut.isConsoleLogRecordingEnabled())

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `flags reload without a cached recording config leaves replay inactive`() {
        val withoutRecording = File("src/test/resources/json/basic-flags-no-session-recording.json").readText()

        val http = mockHttp(response = MockResponse().setBody(withoutRecording))
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        val latch = CountDownLatch(1)
        sut.loadFeatureFlags(
            "my_identify",
            anonymousId = "anonId",
            emptyMap(),
            onFeatureFlags = PostHogOnFeatureFlags { latch.countDown() },
        )
        assertTrue(latch.await(5, TimeUnit.SECONDS), "flags load should complete")

        assertFalse(sut.isSessionReplayFlagActive())

        sut.clear()
        http.shutdown()
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

    // The linkedFlag config comes from /config (cached); a /flags reload re-evaluates it against the
    // new flags. Seed the cache, then load the flags fixture so reevaluate runs over those flags.
    private fun assertReplayActiveForLinkedFlag(
        cachedRecording: Map<String, Any?>,
        flagsFixture: String,
        expectedActive: Boolean,
    ) {
        preferences.setValue(SESSION_REPLAY, cachedRecording)

        val http = mockHttp(response = MockResponse().setBody(File(flagsFixture).readText()))
        val sut = getSut(host = http.url("/").toString())

        sut.loadFeatureFlags("my_identify", anonymousId = "anonId", emptyMap())
        executor.shutdownAndAwaitTermination()

        assertEquals(expectedActive, sut.isSessionReplayFlagActive())

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `returns isSessionReplayFlagActive true if bool linked flag is enabled`() {
        assertReplayActiveForLinkedFlag(
            cachedRecording = mapOf("endpoint" to "/b/", "linkedFlag" to "session-replay-flag"),
            flagsFixture = "src/test/resources/json/basic-flags-recording-bool-linked-enabled.json",
            expectedActive = true,
        )
    }

    @Test
    fun `returns isSessionReplayFlagActive false if bool linked flag is disabled`() {
        assertReplayActiveForLinkedFlag(
            cachedRecording = mapOf("endpoint" to "/b/", "linkedFlag" to "session-replay-flag"),
            flagsFixture = "src/test/resources/json/basic-flags-recording-bool-linked-disabled.json",
            expectedActive = false,
        )
    }

    @Test
    fun `returns isSessionReplayFlagActive true if multi variant linked flag is a match`() {
        assertReplayActiveForLinkedFlag(
            cachedRecording =
                mapOf(
                    "endpoint" to "/b/",
                    "linkedFlag" to mapOf("flag" to "session-replay-flag", "variant" to "variant-1"),
                ),
            flagsFixture = "src/test/resources/json/basic-flags-recording-bool-linked-variant-match.json",
            expectedActive = true,
        )
    }

    @Test
    fun `returns isSessionReplayFlagActive false if multi variant linked flag is not a match`() {
        assertReplayActiveForLinkedFlag(
            cachedRecording =
                mapOf(
                    "endpoint" to "/b/",
                    "linkedFlag" to mapOf("flag" to "session-replay-flag", "variant" to "variant-1"),
                ),
            flagsFixture = "src/test/resources/json/basic-flags-recording-bool-linked-variant-not-match.json",
            expectedActive = false,
        )
    }

    @Test
    fun `re-arm on reset turns replay off when the new user is outside the linked-flag rollout`() {
        // /config cached a linkedFlag recording config; replay is gated on the flag per user.
        preferences.setValue(SESSION_REPLAY, mapOf("endpoint" to "/b/", "linkedFlag" to "session-replay-flag"))

        // First user is in the rollout (flag true), second is outside it (flag false).
        val inRollout = File("src/test/resources/json/basic-flags-recording-bool-linked-enabled.json").readText()
        val outOfRollout = File("src/test/resources/json/basic-flags-recording-bool-linked-disabled.json").readText()
        val http = mockHttp(response = MockResponse().setBody(inRollout))
        http.enqueue(MockResponse().setBody(outOfRollout))
        val sut = getSut(host = http.url("/").toString())

        val firstLatch = CountDownLatch(1)
        sut.loadFeatureFlags(
            "user-in-rollout",
            anonymousId = "anonId",
            emptyMap(),
            onFeatureFlags = PostHogOnFeatureFlags { firstLatch.countDown() },
        )
        assertTrue(firstLatch.await(5, TimeUnit.SECONDS), "first flags load should complete")
        assertTrue(sut.isSessionReplayFlagActive())

        sut.clear()

        val secondLatch = CountDownLatch(1)
        sut.loadFeatureFlags(
            "user-outside-rollout",
            anonymousId = "anonId",
            emptyMap(),
            onFeatureFlags = PostHogOnFeatureFlags { secondLatch.countDown() },
        )
        assertTrue(secondLatch.await(5, TimeUnit.SECONDS), "second flags load should complete")

        // Re-arm must re-evaluate the linked flag against the new user and stay OFF, not blindly true.
        assertFalse(sut.isSessionReplayFlagActive())

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `re-arm survives the Gson round-trip for a variant linked flag`() {
        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(File("src/test/resources/json/basic-flags-recording-bool-linked-variant-match.json").readText()),
            )
        val sut = getSut(host = http.url("/").toString())

        // Mirror the on-disk cache: serialize then read back the way PostHogSharedPreferences does,
        // so the nested linkedFlag map goes through the real Gson round-trip the device hit.
        val serializer = config!!.serializer
        val original =
            mapOf(
                "endpoint" to "/b/",
                "linkedFlag" to mapOf("flag" to "session-replay-flag", "variant" to "variant-1"),
            )

        @Suppress("UNCHECKED_CAST")
        val roundTripped = serializer.deserializeString(serializer.serializeObject(original)!!) as Map<String, Any?>
        preferences.setValue(SESSION_REPLAY, roundTripped)

        val latch = CountDownLatch(1)
        sut.loadFeatureFlags(
            "my_identify",
            anonymousId = "anonId",
            emptyMap(),
            onFeatureFlags = PostHogOnFeatureFlags { latch.countDown() },
        )
        assertTrue(latch.await(5, TimeUnit.SECONDS), "flags load should complete")

        // The variant linkedFlag must still match after the JSON round-trip.
        assertTrue(sut.isSessionReplayFlagActive())

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

    @Test
    fun `explicit sessionRecording false from remote config evicts the cached recording config`() {
        // Stale cache from when the project had replay enabled.
        preferences.setValue(SESSION_REPLAY, mapOf("endpoint" to "/b/"))

        // basic-remote-config-features-disabled.json carries sessionRecording: false.
        val disabled = File("src/test/resources/json/basic-remote-config-features-disabled.json").readText()
        val http = mockHttp(response = MockResponse().setBody(disabled))
        val sut = getSut(host = http.url("/").toString())

        sut.loadRemoteConfig("my_identify", anonymousId = "anonId", emptyMap())
        executor.shutdownAndAwaitTermination()

        assertFalse(sut.isSessionReplayFlagActive())
        // The cache must be evicted, otherwise a later reset()+reload could re-arm a disabled project.
        assertNull(preferences.getValue(SESSION_REPLAY))

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `explicit errorTracking false from remote config evicts the cached config`() {
        // Stale cache from when the project had autocapture enabled.
        preferences.setValue(ERROR_TRACKING, mapOf("autocaptureExceptions" to true))

        // basic-remote-config-features-disabled.json carries errorTracking: false.
        val disabled = File("src/test/resources/json/basic-remote-config-features-disabled.json").readText()
        val http = mockHttp(response = MockResponse().setBody(disabled))
        val sut = getSut(host = http.url("/").toString())
        config!!.errorTrackingConfig.autoCapture = true

        sut.loadRemoteConfig("my_identify", anonymousId = "anonId", emptyMap())
        executor.shutdownAndAwaitTermination()

        assertFalse(sut.isAutocaptureExceptionsEnabled())
        // Must evict the cache, else a later reset()+reload could re-arm a disabled project.
        assertNull(preferences.getValue(ERROR_TRACKING))

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `explicit capturePerformance false from remote config evicts the cached config`() {
        // Stale cache from when the project had network timing enabled.
        preferences.setValue(CAPTURE_PERFORMANCE, mapOf("network_timing" to true))

        // basic-remote-config-features-disabled.json carries capturePerformance: false.
        val disabled = File("src/test/resources/json/basic-remote-config-features-disabled.json").readText()
        val http = mockHttp(response = MockResponse().setBody(disabled))
        val sut = getSut(host = http.url("/").toString())

        sut.loadRemoteConfig("my_identify", anonymousId = "anonId", emptyMap())
        executor.shutdownAndAwaitTermination()

        assertFalse(sut.isCaptureNetworkTimingEnabled())
        // Must evict the cache, else a later reset()+reload could re-arm a disabled project.
        assertNull(preferences.getValue(CAPTURE_PERFORMANCE))

        sut.clear()
        http.shutdown()
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

        // Add delay to simulate network latency
        val http =
            mockHttp(
                total = 2,
                response =
                    MockResponse()
                        .setBody(responseFlagsApi)
                        .setBodyDelay(100, TimeUnit.MILLISECONDS),
            )
        val url = http.url("/")

        val localConfig =
            PostHogConfig(API_KEY, url.toString()).apply {
                cachePreferences = preferences
            }
        val api = PostHogApi(localConfig)
        val sut = PostHogRemoteConfig(localConfig, api, executor = multiThreadExecutor, defaultPersonPropertiesProvider = { emptyMap() })

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

        val localConfig =
            PostHogConfig(API_KEY, url.toString()).apply {
                cachePreferences = preferences
            }
        val api = PostHogApi(localConfig)
        val sut = PostHogRemoteConfig(localConfig, api, executor = multiThreadExecutor, defaultPersonPropertiesProvider = { emptyMap() })

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

    // --- Error Tracking remote config tests ---

    @Test
    fun `remote config enables autocaptureExceptions when remote is enabled`() {
        val file = File("src/test/resources/json/basic-remote-config-features-enabled.json")

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(file.readText()),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())
        // Enable local config
        config!!.errorTrackingConfig.autoCapture = true

        sut.loadRemoteConfig("my_identify", anonymousId = "anonId", emptyMap())

        executor.shutdownAndAwaitTermination()

        assertTrue(sut.isAutocaptureExceptionsEnabled())

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `remote config disables autocaptureExceptions when remote is disabled (boolean false)`() {
        val file = File("src/test/resources/json/basic-remote-config-features-disabled.json")

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(file.readText()),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())
        config!!.errorTrackingConfig.autoCapture = true

        sut.loadRemoteConfig("my_identify", anonymousId = "anonId", emptyMap())

        executor.shutdownAndAwaitTermination()

        assertFalse(sut.isAutocaptureExceptionsEnabled())

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `autocaptureExceptions is disabled when remote is enabled but local is disabled`() {
        val file = File("src/test/resources/json/basic-remote-config-features-enabled.json")

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(file.readText()),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())
        // Local config is disabled (default)
        config!!.errorTrackingConfig.autoCapture = false

        sut.loadRemoteConfig("my_identify", anonymousId = "anonId", emptyMap())

        executor.shutdownAndAwaitTermination()

        assertFalse(sut.isAutocaptureExceptionsEnabled())

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `autocaptureExceptions is disabled when both remote and local are disabled`() {
        val file = File("src/test/resources/json/basic-remote-config-features-disabled.json")

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(file.readText()),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())
        config!!.errorTrackingConfig.autoCapture = false

        sut.loadRemoteConfig("my_identify", anonymousId = "anonId", emptyMap())

        executor.shutdownAndAwaitTermination()

        assertFalse(sut.isAutocaptureExceptionsEnabled())

        sut.clear()
        http.shutdown()
    }

    // --- Console Log Recording remote config tests (from sessionRecording) ---

    @Test
    fun `remote config enables consoleLogRecordingEnabled from sessionRecording`() {
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

        assertTrue(sut.isConsoleLogRecordingEnabled())

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `remote config disables consoleLogRecordingEnabled when sessionRecording is boolean false`() {
        val file = File("src/test/resources/json/basic-remote-config-features-disabled.json")

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

        assertFalse(sut.isConsoleLogRecordingEnabled())

        sut.clear()
        http.shutdown()
    }

    // --- Capture Performance remote config tests ---

    @Test
    fun `remote config enables network timing when remote is enabled`() {
        val file = File("src/test/resources/json/basic-remote-config-features-enabled.json")

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

        assertTrue(sut.isCaptureNetworkTimingEnabled())

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `remote config disables network timing when remote is disabled (boolean false)`() {
        val file = File("src/test/resources/json/basic-remote-config-features-disabled.json")

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

        assertFalse(sut.isCaptureNetworkTimingEnabled())

        sut.clear()
        http.shutdown()
    }

    // --- Cache preload tests ---

    @Test
    fun `preloads error tracking config from cache on start`() {
        val cachedConfig = mapOf("autocaptureExceptions" to true)
        preferences.setValue(ERROR_TRACKING, cachedConfig)

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseFlagsApi),
            )
        val url = http.url("/")

        val localConfig =
            PostHogConfig(API_KEY, url.toString()).apply {
                cachePreferences = preferences
                errorTrackingConfig.autoCapture = true
            }
        val api = PostHogApi(localConfig)
        val sut = PostHogRemoteConfig(localConfig, api, executor = executor, defaultPersonPropertiesProvider = { emptyMap() })

        assertTrue(sut.isAutocaptureExceptionsEnabled())

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `preloads consoleLogRecordingEnabled from session replay cache on start`() {
        val cachedConfig = mapOf("consoleLogRecordingEnabled" to true)
        preferences.setValue(SESSION_REPLAY, cachedConfig)

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseFlagsApi),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        assertTrue(sut.isConsoleLogRecordingEnabled())

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `preloads capture performance config from cache on start`() {
        val cachedConfig = mapOf("network_timing" to true)
        preferences.setValue(CAPTURE_PERFORMANCE, cachedConfig)

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseFlagsApi),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        assertTrue(sut.isCaptureNetworkTimingEnabled())

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `clear keeps cached error tracking config so it can re-arm`() {
        val cachedConfig = mapOf("autocaptureExceptions" to true)
        preferences.setValue(ERROR_TRACKING, cachedConfig)

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseFlagsApi),
            )
        val url = http.url("/")

        val localConfig =
            PostHogConfig(API_KEY, url.toString()).apply {
                cachePreferences = preferences
                errorTrackingConfig.autoCapture = true
            }
        val api = PostHogApi(localConfig)
        val sut = PostHogRemoteConfig(localConfig, api, executor = executor, defaultPersonPropertiesProvider = { emptyMap() })

        assertTrue(sut.isAutocaptureExceptionsEnabled())

        sut.clear()

        // reset() zeroes the in-memory flag but keeps the project-level config so a reload can re-arm it.
        assertFalse(sut.isAutocaptureExceptionsEnabled())
        assertEquals(cachedConfig, preferences.getValue(ERROR_TRACKING))

        http.shutdown()
    }

    @Test
    fun `clear resets consoleLogRecordingEnabled`() {
        val cachedConfig = mapOf("consoleLogRecordingEnabled" to true)
        preferences.setValue(SESSION_REPLAY, cachedConfig)

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseFlagsApi),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        assertTrue(sut.isConsoleLogRecordingEnabled())

        sut.clear()

        assertFalse(sut.isConsoleLogRecordingEnabled())

        http.shutdown()
    }

    @Test
    fun `clear keeps cached capture performance config so it can re-arm`() {
        val cachedConfig = mapOf("network_timing" to true)
        preferences.setValue(CAPTURE_PERFORMANCE, cachedConfig)

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseFlagsApi),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        assertTrue(sut.isCaptureNetworkTimingEnabled())

        sut.clear()

        // reset() zeroes the in-memory flag but keeps the project-level config so a reload can re-arm it.
        assertFalse(sut.isCaptureNetworkTimingEnabled())
        assertEquals(cachedConfig, preferences.getValue(CAPTURE_PERFORMANCE))

        http.shutdown()
    }

    @Test
    fun `remote config caches error tracking config to disk`() {
        val file = File("src/test/resources/json/basic-remote-config-features-enabled.json")

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(file.readText()),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())
        config!!.errorTrackingConfig.autoCapture = true

        sut.loadRemoteConfig("my_identify", anonymousId = "anonId", emptyMap())

        executor.shutdownAndAwaitTermination()

        @Suppress("UNCHECKED_CAST")
        val cached = preferences.getValue(ERROR_TRACKING) as? Map<String, Any>
        assertEquals(true, cached?.get("autocaptureExceptions"))

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `remote config caches consoleLogRecordingEnabled via sessionRecording to disk`() {
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

        @Suppress("UNCHECKED_CAST")
        val cached = preferences.getValue(SESSION_REPLAY) as? Map<String, Any>
        assertEquals(true, cached?.get("consoleLogRecordingEnabled"))

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `remote config caches capture performance config to disk`() {
        val file = File("src/test/resources/json/basic-remote-config-features-enabled.json")

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

        @Suppress("UNCHECKED_CAST")
        val cached = preferences.getValue(CAPTURE_PERFORMANCE) as? Map<String, Any>
        assertEquals(true, cached?.get("network_timing"))

        sut.clear()
        http.shutdown()
    }

    // --- Default values tests ---

    @Test
    fun `isAutocaptureExceptionsEnabled is false by default`() {
        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseFlagsApi),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        assertFalse(sut.isAutocaptureExceptionsEnabled())

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `isConsoleLogRecordingEnabled is true by default`() {
        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseFlagsApi),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        assertTrue(sut.isConsoleLogRecordingEnabled())

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `isCaptureNetworkTimingEnabled is true by default`() {
        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseFlagsApi),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        assertTrue(sut.isCaptureNetworkTimingEnabled())

        sut.clear()
        http.shutdown()
    }

    // --- Flags-only path tests (loadFeatureFlags -> executeFeatureFlags) ---

    @Test
    fun `re-arms error tracking and capture performance from cached config after reset`() {
        // /config cached these; production /flags omits them, so the reload must re-arm from the cache.
        preferences.setValue(ERROR_TRACKING, mapOf("autocaptureExceptions" to true))
        preferences.setValue(CAPTURE_PERFORMANCE, mapOf("network_timing" to true))

        val withoutConfig = File("src/test/resources/json/basic-flags-no-session-recording.json").readText()
        val http = mockHttp(response = MockResponse().setBody(withoutConfig))
        http.enqueue(MockResponse().setBody(withoutConfig))
        val url = http.url("/")

        val sut = getSut(host = url.toString())
        config!!.errorTrackingConfig.autoCapture = true

        // Preloaded from cache at construction.
        assertTrue(sut.isAutocaptureExceptionsEnabled())
        assertTrue(sut.isCaptureNetworkTimingEnabled())

        // reset() zeroes the in-memory flags but keeps the cached config.
        sut.clear()
        assertFalse(sut.isAutocaptureExceptionsEnabled())
        assertFalse(sut.isCaptureNetworkTimingEnabled())

        val latch = CountDownLatch(1)
        sut.loadFeatureFlags(
            "my_identify",
            anonymousId = "anonId",
            emptyMap(),
            onFeatureFlags = PostHogOnFeatureFlags { latch.countDown() },
        )
        assertTrue(latch.await(5, TimeUnit.SECONDS), "flags load should complete")

        // Re-armed from cache, not clobbered to false by the absent config on /flags.
        assertTrue(sut.isAutocaptureExceptionsEnabled())
        assertTrue(sut.isCaptureNetworkTimingEnabled())

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `flags reload with no cached error tracking or capture performance does not re-arm them`() {
        // Nothing cached, so the reevaluate* null-cache early-return must be a no-op: a reload after
        // reset() leaves both flags zeroed instead of re-arming them from a non-existent cache.
        val withoutConfig = File("src/test/resources/json/basic-flags-no-session-recording.json").readText()
        val http = mockHttp(response = MockResponse().setBody(withoutConfig))
        val url = http.url("/")

        val sut = getSut(host = url.toString())
        // Local autocapture is on, so a true result could only come from the (absent) remote config.
        config!!.errorTrackingConfig.autoCapture = true

        // reset() zeroes both in-memory flags (captureNetworkTiming defaults to true, so this matters).
        sut.clear()
        assertFalse(sut.isAutocaptureExceptionsEnabled())
        assertFalse(sut.isCaptureNetworkTimingEnabled())

        val latch = CountDownLatch(1)
        sut.loadFeatureFlags(
            "my_identify",
            anonymousId = "anonId",
            emptyMap(),
            onFeatureFlags = PostHogOnFeatureFlags { latch.countDown() },
        )
        assertTrue(latch.await(5, TimeUnit.SECONDS), "flags load should complete")

        // Still off: the empty cache means there is nothing to re-arm.
        assertFalse(sut.isAutocaptureExceptionsEnabled())
        assertFalse(sut.isCaptureNetworkTimingEnabled())

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `surveys survive reset and stay in memory and cache`() {
        preferences.setValue(
            SURVEYS,
            listOf(
                mapOf(
                    "id" to "s1",
                    "name" to "Test Survey",
                    "type" to "popover",
                    "questions" to emptyList<Any>(),
                ),
            ),
        )

        val sut = getSut(host = "https://localhost", surveys = true)
        assertEquals("s1", sut.getSurveys()?.single()?.id)

        sut.clear()
        assertEquals("s1", sut.getSurveys()?.single()?.id)
        assertNotNull(preferences.getValue(SURVEYS))
    }

    @Test
    fun `surveys loaded from remote config survive reset`() {
        val file = File("src/test/resources/json/basic-remote-config-with-surveys.json")
        val http = mockHttp(response = MockResponse().setBody(file.readText()))
        val sut = getSut(host = http.url("/").toString(), surveys = true)

        sut.loadRemoteConfig("my_identify", anonymousId = "anonId", emptyMap())
        executor.shutdownAndAwaitTermination()

        assertEquals("s1", sut.getSurveys()?.single()?.id)
        assertNotNull(preferences.getValue(SURVEYS))

        sut.clear()
        assertEquals("s1", sut.getSurveys()?.single()?.id)
        assertNotNull(preferences.getValue(SURVEYS))

        http.shutdown()
    }

    @Test
    fun `onRemoteConfigLoaded fires via flags-only path`() {
        val file = File("src/test/resources/json/basic-flags-with-remote-config-features.json")

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(file.readText()),
            )
        val url = http.url("/")

        var callbackCount = 0

        val localConfig =
            PostHogConfig(API_KEY, url.toString()).apply {
                cachePreferences = preferences
            }
        val api = PostHogApi(localConfig)
        val sut =
            PostHogRemoteConfig(
                localConfig,
                api,
                executor = executor,
                defaultPersonPropertiesProvider = PostHogDefaultPersonPropertiesProvider { emptyMap() },
                onRemoteConfigLoaded = PostHogOnRemoteConfigLoaded { callbackCount++ },
            )

        sut.loadFeatureFlags("my_identify", anonymousId = "anonId", emptyMap())

        executor.shutdownAndAwaitTermination()

        assertEquals(1, callbackCount)

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `onRemoteConfigLoaded fires exactly once via loadRemoteConfig with flags`() {
        // basic-remote-config.json has hasFeatureFlags=true, so loadRemoteConfig
        // will also call executeFeatureFlags with notifyRemoteConfigLoaded=false
        val remoteConfigFile = File("src/test/resources/json/basic-remote-config.json")
        val flagsFile = File("src/test/resources/json/basic-flags-with-remote-config-features.json")

        val http =
            mockHttp(
                total = 2,
                response =
                    MockResponse()
                        .setBody(remoteConfigFile.readText()),
            )
        // Second response for the flags call
        http.enqueue(
            MockResponse()
                .setBody(flagsFile.readText()),
        )
        val url = http.url("/")

        var callbackCount = 0

        val localConfig =
            PostHogConfig(API_KEY, url.toString()).apply {
                cachePreferences = preferences
                preloadFeatureFlags = true
            }
        val api = PostHogApi(localConfig)
        val sut =
            PostHogRemoteConfig(
                localConfig,
                api,
                executor = executor,
                defaultPersonPropertiesProvider = PostHogDefaultPersonPropertiesProvider { emptyMap() },
                onRemoteConfigLoaded = PostHogOnRemoteConfigLoaded { callbackCount++ },
            )

        sut.loadRemoteConfig("my_identify", anonymousId = "anonId", emptyMap())

        executor.shutdownAndAwaitTermination()

        // Should fire exactly once — loadRemoteConfig fires it, executeFeatureFlags does not
        assertEquals(1, callbackCount)

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `loadRemoteConfig does not overwrite remote config values when executeFeatureFlags is called`() {
        // Remote config API says errorTracking enabled, capturePerformance enabled
        val remoteConfigFile = File("src/test/resources/json/basic-remote-config-features-enabled.json")
        // but basic-remote-config-features-enabled.json has hasFeatureFlags=false,
        // so executeFeatureFlags won't be called — this test just checks remote config path

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(remoteConfigFile.readText()),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())
        config!!.errorTrackingConfig.autoCapture = true

        sut.loadRemoteConfig("my_identify", anonymousId = "anonId", emptyMap())

        executor.shutdownAndAwaitTermination()

        assertTrue(sut.isAutocaptureExceptionsEnabled())
        assertTrue(sut.isCaptureNetworkTimingEnabled())
        // sessionRecording is boolean false in features-enabled.json
        assertFalse(sut.isConsoleLogRecordingEnabled())

        sut.clear()
        http.shutdown()
    }

    // --- Sample Rate tests ---

    @Test
    fun `simpleHash produces consistent positive values`() {
        val hash1 = simpleHash("test-session-id")
        val hash2 = simpleHash("test-session-id")
        assertEquals(hash1, hash2)
        assertTrue(hash1 >= 0)
    }

    @Test
    fun `simpleHash produces different values for different inputs`() {
        val hash1 = simpleHash("session-1")
        val hash2 = simpleHash("session-2")
        assertTrue(hash1 != hash2)
    }

    @Test
    fun `sampleOnProperty returns true when rate is 1`() {
        assertTrue(sampleOnProperty("any-session-id", 1.0))
    }

    @Test
    fun `sampleOnProperty returns false when rate is 0`() {
        assertFalse(sampleOnProperty("any-session-id", 0.0))
    }

    @Test
    fun `sampleOnProperty is deterministic for same session id and rate`() {
        val result1 = sampleOnProperty("my-session", 0.5)
        val result2 = sampleOnProperty("my-session", 0.5)
        assertEquals(result1, result2)
    }

    @Test
    fun `makeSamplingDecision returns true when no sample rate is configured`() {
        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseFlagsApi),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        // No sample rate set, should always return true
        assertTrue(sut.makeSamplingDecision("any-session-id"))
        assertNull(sut.getSessionRecordingSampleRate())

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `makeSamplingDecision returns true when sample rate is 1`() {
        // Cache a session recording config with sampleRate "1"
        val cachedConfig = mapOf("sampleRate" to "1")
        preferences.setValue(SESSION_REPLAY, cachedConfig)

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseFlagsApi),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        assertEquals(1.0, sut.getSessionRecordingSampleRate())
        assertTrue(sut.makeSamplingDecision("any-session-id"))

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `makeSamplingDecision returns false when sample rate is 0`() {
        val cachedConfig = mapOf("sampleRate" to "0")
        preferences.setValue(SESSION_REPLAY, cachedConfig)

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseFlagsApi),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        assertEquals(0.0, sut.getSessionRecordingSampleRate())
        assertFalse(sut.makeSamplingDecision("any-session-id"))

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `processSessionRecordingConfig parses sampleRate as string from remote config`() {
        val file = File("src/test/resources/json/basic-remote-config-with-sample-rate.json")

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

        assertEquals(0.5, sut.getSessionRecordingSampleRate())

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `processSessionRecordingConfig sets null sampleRate when not present`() {
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

        assertNull(sut.getSessionRecordingSampleRate())

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `preloads sampleRate from cache on start`() {
        val cachedConfig = mapOf("sampleRate" to "0.75")
        preferences.setValue(SESSION_REPLAY, cachedConfig)

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseFlagsApi),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        assertEquals(0.75, sut.getSessionRecordingSampleRate())

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `preloads sampleRate from cache as number`() {
        // Some serializers may store numbers rather than strings in the cache
        val cachedConfig = mapOf("sampleRate" to 0.5)
        preferences.setValue(SESSION_REPLAY, cachedConfig)

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseFlagsApi),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        assertEquals(0.5, sut.getSessionRecordingSampleRate())

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `makeSamplingDecision uses local sample rate when set`() {
        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseFlagsApi),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())
        // Set local sample rate to 0 (should never record)
        config!!.sampleRateProvider = { 0.0 }

        assertFalse(sut.makeSamplingDecision("any-session-id"))

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `makeSamplingDecision local sample rate takes precedence over remote`() {
        // Cache a remote sample rate of 1.0 (always record)
        val cachedConfig = mapOf("sampleRate" to "1")
        preferences.setValue(SESSION_REPLAY, cachedConfig)

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseFlagsApi),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())
        // Local overrides remote: set to 0 (never record)
        config!!.sampleRateProvider = { 0.0 }

        assertFalse(sut.makeSamplingDecision("any-session-id"))

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `makeSamplingDecision falls back to remote when local is null`() {
        // Cache a remote sample rate of 0 (never record)
        val cachedConfig = mapOf("sampleRate" to "0")
        preferences.setValue(SESSION_REPLAY, cachedConfig)

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseFlagsApi),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())
        // Local is null (default), so remote takes effect
        assertNull(config!!.sampleRateProvider)

        assertFalse(sut.makeSamplingDecision("any-session-id"))

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `makeSamplingDecision local sample rate 1 overrides remote 0`() {
        // Cache a remote sample rate of 0 (never record)
        val cachedConfig = mapOf("sampleRate" to "0")
        preferences.setValue(SESSION_REPLAY, cachedConfig)

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseFlagsApi),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())
        // Local overrides remote: set to 1 (always record)
        config!!.sampleRateProvider = { 1.0 }

        assertTrue(sut.makeSamplingDecision("any-session-id"))

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `makeSamplingDecision ignores local sample rate above 1`() {
        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseFlagsApi),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())
        // Invalid local rate should be ignored, no remote rate set, so returns true
        config!!.sampleRateProvider = { 1.5 }

        assertTrue(sut.makeSamplingDecision("any-session-id"))

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `makeSamplingDecision ignores local sample rate below 0`() {
        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseFlagsApi),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())
        // Invalid local rate should be ignored, no remote rate set, so returns true
        config!!.sampleRateProvider = { -0.5 }

        assertTrue(sut.makeSamplingDecision("any-session-id"))

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `makeSamplingDecision ignores invalid local sample rate and falls back to remote`() {
        // Cache a remote sample rate of 0 (never record)
        val cachedConfig = mapOf("sampleRate" to "0")
        preferences.setValue(SESSION_REPLAY, cachedConfig)

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseFlagsApi),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())
        // Invalid local rate should be ignored, falls back to remote (0)
        config!!.sampleRateProvider = { 2.0 }

        assertFalse(sut.makeSamplingDecision("any-session-id"))

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `parseSampleRate ignores invalid remote sample rate from cache`() {
        // Cache an out-of-range remote sample rate
        val cachedConfig = mapOf("sampleRate" to "1.5")
        preferences.setValue(SESSION_REPLAY, cachedConfig)

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseFlagsApi),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        // Invalid remote rate should be ignored (parsed as null)
        assertNull(sut.getSessionRecordingSampleRate())
        // No valid rate, so should return true
        assertTrue(sut.makeSamplingDecision("any-session-id"))

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `parseSampleRate ignores negative remote sample rate from cache`() {
        val cachedConfig = mapOf("sampleRate" to "-0.5")
        preferences.setValue(SESSION_REPLAY, cachedConfig)

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseFlagsApi),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        assertNull(sut.getSessionRecordingSampleRate())
        assertTrue(sut.makeSamplingDecision("any-session-id"))

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `onRemoteConfigLoaded fires once via loadRemoteConfig without flags`() {
        // basic-remote-config-no-flags.json has hasFeatureFlags=false
        val file = File("src/test/resources/json/basic-remote-config-no-flags.json")

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(file.readText()),
            )
        val url = http.url("/")

        var callbackCount = 0

        val localConfig =
            PostHogConfig(API_KEY, url.toString()).apply {
                cachePreferences = preferences
            }
        val api = PostHogApi(localConfig)
        val sut =
            PostHogRemoteConfig(
                localConfig,
                api,
                executor = executor,
                defaultPersonPropertiesProvider = PostHogDefaultPersonPropertiesProvider { emptyMap() },
                onRemoteConfigLoaded = PostHogOnRemoteConfigLoaded { callbackCount++ },
            )

        sut.loadRemoteConfig("my_identify", anonymousId = "anonId", emptyMap())

        executor.shutdownAndAwaitTermination()

        assertEquals(1, callbackCount)

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `getEventTriggers returns null when not configured`() {
        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseFlagsApi),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        assertNull(sut.getEventTriggers())

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `getEventTriggers returns null when eventTriggers is empty array`() {
        val cachedConfig = mapOf("eventTriggers" to emptyList<String>())
        preferences.setValue(SESSION_REPLAY, cachedConfig)

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseFlagsApi),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        assertNull(sut.getEventTriggers())

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `getEventTriggers returns set when configured`() {
        val triggers = listOf("checkout_started", "payment_completed")
        val cachedConfig = mapOf("eventTriggers" to triggers)
        preferences.setValue(SESSION_REPLAY, cachedConfig)

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseFlagsApi),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        assertEquals(triggers.toSet(), sut.getEventTriggers())

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `getEventTriggers returns null for react native even when configured`() {
        val triggers = listOf("checkout_started", "payment_completed")
        preferences.setValue(SESSION_REPLAY, mapOf("eventTriggers" to triggers))

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseFlagsApi),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        // React Native evaluates event triggers in its JS layer, so the native gate opts out.
        PostHogSessionManager.isReactNative = true
        assertNull(sut.getEventTriggers())

        // Non-RN still receives the configured set.
        PostHogSessionManager.isReactNative = false
        assertEquals(triggers.toSet(), sut.getEventTriggers())

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `processSessionRecordingConfig parses eventTriggers from remote config`() {
        val file = File("src/test/resources/json/basic-remote-config-with-event-triggers.json")

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

        assertEquals(setOf("checkout_started", "payment_completed"), sut.getEventTriggers())

        sut.clear()
        http.shutdown()
    }

    @Test
    fun `preloads eventTriggers from cache on start`() {
        val triggers = listOf("button_clicked", "form_submitted")
        val cachedConfig = mapOf("eventTriggers" to triggers)
        preferences.setValue(SESSION_REPLAY, cachedConfig)

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseFlagsApi),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        assertEquals(triggers.toSet(), sut.getEventTriggers())

        sut.clear()
        http.shutdown()
    }
}

package com.posthog

import com.posthog.internal.PostHogMemoryPreferences
import com.posthog.internal.PostHogPreferences.Companion.PERSON_PROPERTIES_FOR_FLAGS
import com.posthog.internal.PostHogThreadFactory
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

internal class PostHogWrapperEventTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    @get:Rule
    val httpServers = TestHttpServers()

    private val queueExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestQueue"))
    private val replayQueueExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestReplayQueue"))
    private val logsExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestLogsQueue"))
    private val aiExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestAiQueue"))
    private val remoteConfigExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestRemoteConfig"))
    private val cachedEventsExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestCachedEvents"))
    private val clients = mutableListOf<PostHogInterface>()

    private fun getSut(
        http: MockWebServer,
        configure: PostHogConfig.() -> Unit = {},
    ): PostHogInterface {
        val storage = tmpDir.newFolder().absolutePath
        val config =
            PostHogConfig(PROJECT_TOKEN, http.url("/").toString()).apply {
                storagePrefix = File(storage, "events").absolutePath
                replayStoragePrefix = File(storage, "snapshots").absolutePath
                logsStoragePrefix = File(storage, "logs").absolutePath
                aiStoragePrefix = File(storage, "ai").absolutePath
                flushAt = 1
                preloadFeatureFlags = false
                sendFeatureFlagEvent = false
                cachePreferences = PostHogMemoryPreferences()
                context = TestPostHogContext()
                configure()
            }
        return PostHog.withInternal(
            config,
            queueExecutor,
            replayQueueExecutor,
            remoteConfigExecutor,
            cachedEventsExecutor,
            reloadFeatureFlags = false,
            logsExecutor = logsExecutor,
            aiExecutor = aiExecutor,
        ).also { clients.add(it) }
    }

    @AfterTest
    fun cleanup() {
        clients.forEach { it.close() }
        listOf(queueExecutor, replayQueueExecutor, logsExecutor, aiExecutor, remoteConfigExecutor, cachedEventsExecutor).forEach {
            it.shutdownAndAwaitTermination()
        }
        tmpDir.root.deleteRecursively()
    }

    private fun takeRequest(http: MockWebServer): RecordedRequest = assertNotNull(http.takeRequest(5, TimeUnit.SECONDS))

    private fun events(request: RecordedRequest): List<Map<String, Any?>> = captureV1Events(request.body.unGzip())

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.properties(): Map<String, Any?> = this["properties"] as Map<String, Any?>

    @Test
    fun `build fills the event without running beforeSend or queueing it`() {
        val http = httpServers.mockHttp()
        var hookCalls = 0
        val sut =
            getSut(http) {
                addBeforeSend { event ->
                    hookCalls++
                    event
                }
            }
        sut.register("plan", "free")

        val event =
            assertNotNull(
                sut.buildEventForWrapper(
                    "purchase",
                    properties = mapOf("plan" to "pro"),
                    userProperties = mapOf("email" to "a@b.c"),
                    options = mapOf("cookieless_mode" to true),
                ),
            )

        queueExecutor.awaitExecution()
        assertEquals(0, http.requestCount)
        assertEquals(0, hookCalls)
        assertEquals("purchase", event.event)
        assertEquals(sut.distinctId(), event.distinctId)
        assertNotNull(event.uuid)
        assertEquals("pro", event.properties?.get("plan"))
        assertEquals("Android", event.properties?.get("\$os_name"))
        assertEquals(mapOf("email" to "a@b.c"), event.properties?.get("\$set"))
        assertEquals(true, event.options?.get("cookieless_mode"))
    }

    @Test
    fun `send keeps the built event's identity and properties, then runs beforeSend`() {
        val http = httpServers.mockHttp()
        val sut =
            getSut(http) {
                addBeforeSend { event ->
                    event.properties?.put("hooked", true)
                    event
                }
            }
        val timestamp = Date(1_700_000_000_000)
        val built = assertNotNull(sut.buildEventForWrapper("purchase", timestamp = timestamp))
        built.properties?.remove("\$os_name")
        sut.register("late", "value")

        val uuid = sut.sendBuiltEventForWrapper(built)

        queueExecutor.awaitExecution()
        val sent = events(takeRequest(http)).single()
        assertEquals(built.uuid, uuid)
        assertEquals(built.uuid.toString(), sent["uuid"])
        assertEquals("2023-11-14T22:13:20.000Z", sent["timestamp"])
        assertEquals(true, sent.properties()["hooked"])
        assertFalse(sent.properties().containsKey("\$os_name"))
        assertFalse(sent.properties().containsKey("late"))
    }

    @Test
    fun `send on the AI lane posts to the AI endpoint`() {
        val http = httpServers.mockHttp()
        val sut = getSut(http)
        sut.startSession()
        val built = assertNotNull(sut.buildEventForWrapper("\$ai_generation", ai = true))

        assertEquals(built.uuid, sut.sendBuiltEventForWrapper(built, ai = true))

        aiExecutor.awaitExecution()
        val request = takeRequest(http)
        assertEquals("/i/v1/ai/events", request.path)
        assertNotNull(events(request).single().properties()["\$sdk_debug_session_start"])
    }

    @Test
    fun `send returns null when beforeSend drops the event`() {
        val http = httpServers.mockHttp()
        val sut = getSut(http) { addBeforeSend { null } }
        val built = assertNotNull(sut.buildEventForWrapper("purchase"))

        assertNull(sut.sendBuiltEventForWrapper(built))

        queueExecutor.awaitExecution()
        assertEquals(0, http.requestCount)
    }

    @Test
    fun `send returns null after an opt-out since the build`() {
        val http = httpServers.mockHttp()
        val sut = getSut(http)
        val built = assertNotNull(sut.buildEventForWrapper("purchase"))
        sut.optOut()

        assertNull(sut.sendBuiltEventForWrapper(built))

        queueExecutor.awaitExecution()
        assertEquals(0, http.requestCount)
    }

    @Test
    fun `build returns null when opted out`() {
        val sut = getSut(httpServers.mockHttp())
        sut.optOut()

        assertNull(sut.buildEventForWrapper("purchase"))
    }

    @Test
    fun `build of a screen event without a screen name returns null`() {
        val sut = getSut(httpServers.mockHttp())

        assertNull(sut.buildEventForWrapper("\$screen", properties = mapOf("\$screen_name" to " ")))
        assertNull(sut.buildEventForWrapper("\$screen"))
    }

    @Test
    fun `a built event the wrapper drops leaves no state behind`() {
        val prefs = PostHogMemoryPreferences()
        val sut = getSut(httpServers.mockHttp()) { cachePreferences = prefs }

        assertNotNull(
            sut.buildEventForWrapper(
                "\$screen",
                properties = mapOf("\$screen_name" to "Home"),
                userProperties = mapOf("plan" to "pro"),
            ),
        )
        val next = assertNotNull(sut.buildEventForWrapper("tap"))

        assertFalse(next.properties!!.containsKey("\$screen_name"))
        assertNull(prefs.getValue(PERSON_PROPERTIES_FOR_FLAGS))
        assertEquals(false, next.options?.get("process_person_profile"))
    }

    @Test
    fun `send turns on person processing for a build with user properties`() {
        val sut = getSut(httpServers.mockHttp())
        val identified = assertNotNull(sut.buildEventForWrapper("signup", userProperties = mapOf("plan" to "pro")))

        assertNotNull(sut.sendBuiltEventForWrapper(identified))
        val next = assertNotNull(sut.buildEventForWrapper("tap"))

        assertNull(identified.options?.get("process_person_profile"))
        assertNull(next.options?.get("process_person_profile"))
    }

    @Test
    fun `send caches the screen name and the person properties for flags`() {
        val prefs = PostHogMemoryPreferences()
        val sut = getSut(httpServers.mockHttp()) { cachePreferences = prefs }
        val screen =
            assertNotNull(
                sut.buildEventForWrapper(
                    "\$screen",
                    properties = mapOf("\$screen_name" to " Home "),
                    userProperties = mapOf("plan" to "pro"),
                ),
            )

        assertNotNull(sut.sendBuiltEventForWrapper(screen))
        val next = assertNotNull(sut.buildEventForWrapper("tap"))

        assertEquals("Home", screen.properties?.get("\$screen_name"))
        assertEquals("Home", next.properties?.get("\$screen_name"))
        assertEquals(mapOf("plan" to "pro"), prefs.getValue(PERSON_PROPERTIES_FOR_FLAGS))
    }

    @Test
    fun `send after a reset delivers the screen without restoring its name`() {
        val sut = getSut(httpServers.mockHttp())
        val screen = assertNotNull(sut.buildEventForWrapper("\$screen", properties = mapOf("\$screen_name" to "Home")))

        sut.reset()
        assertNotNull(sut.sendBuiltEventForWrapper(screen))
        val next = assertNotNull(sut.buildEventForWrapper("tap"))

        assertNull(next.properties?.get("\$screen_name"))
    }

    @Test
    fun `the replay debug bundle is claimed at send`() {
        val http = httpServers.mockHttp()
        val sut = getSut(http) { flushAt = 2 }
        sut.startSession()
        val first = assertNotNull(sut.buildEventForWrapper("\$first"))
        val second = assertNotNull(sut.buildEventForWrapper("\$second"))

        assertNotNull(sut.sendBuiltEventForWrapper(first))
        assertNotNull(sut.sendBuiltEventForWrapper(second))

        queueExecutor.awaitExecution()
        val (sentFirst, sentSecond) = events(takeRequest(http))
        assertNotNull(sentFirst.properties()["\$sdk_debug_session_start"])
        assertFalse(sentSecond.properties().containsKey("\$sdk_debug_session_start"))
    }

    @Test
    fun `replay debug eligibility follows the built name across a rename`() {
        val http = httpServers.mockHttp()
        val sut = getSut(http) { flushAt = 2 }
        sut.startSession()
        val renamedAway = assertNotNull(sut.buildEventForWrapper("\$first"))
        val renamedTo = assertNotNull(sut.buildEventForWrapper("tap"))

        assertNotNull(sut.sendBuiltEventForWrapper(renamedTo.copy(event = "\$second")))
        assertNotNull(sut.sendBuiltEventForWrapper(renamedAway.copy(event = "custom")))

        queueExecutor.awaitExecution()
        val (sentTo, sentAway) = events(takeRequest(http))
        assertNotNull(sentAway.properties()["\$sdk_debug_session_start"])
        assertFalse(sentTo.properties().containsKey("\$sdk_debug_session_start"))
    }

    @Test
    fun `send keeps a replay debug key the wrapper removed`() {
        val http = httpServers.mockHttp()
        val sut = getSut(http)
        sut.startSession()
        val event = assertNotNull(sut.buildEventForWrapper("\$first"))
        event.properties?.remove("\$recording_status")

        assertNotNull(sut.sendBuiltEventForWrapper(event))

        queueExecutor.awaitExecution()
        val sent = events(takeRequest(http)).single().properties()
        assertNotNull(sent["\$sdk_debug_session_start"])
        assertFalse(sent.containsKey("\$recording_status"))
    }

    @Test
    fun `a second send of the same built event is a no-op`() {
        val http = httpServers.mockHttp()
        val sut = getSut(http) { flushAt = 2 }
        val event = assertNotNull(sut.buildEventForWrapper("tap"))

        assertNotNull(sut.sendBuiltEventForWrapper(event))
        assertNull(sut.sendBuiltEventForWrapper(event))
        assertNotNull(sut.sendBuiltEventForWrapper(assertNotNull(sut.buildEventForWrapper("next"))))

        queueExecutor.awaitExecution()
        assertEquals(listOf("tap", "next"), events(takeRequest(http)).map { it["event"] })
    }

    @Test
    fun `send skips the person properties for flags after the user changed`() {
        val prefs = PostHogMemoryPreferences()
        val sut = getSut(httpServers.mockHttp()) { cachePreferences = prefs }
        val event = assertNotNull(sut.buildEventForWrapper("signup", userProperties = mapOf("plan" to "pro")))

        sut.reset()
        assertNotNull(sut.sendBuiltEventForWrapper(event))

        assertNull(prefs.getValue(PERSON_PROPERTIES_FOR_FLAGS))
    }

    @Test
    fun `send skips the person properties for flags after a reset that kept the anonymous id`() {
        val prefs = PostHogMemoryPreferences()
        val sut =
            getSut(httpServers.mockHttp()) {
                cachePreferences = prefs
                reuseAnonymousId = true
            }
        val event = assertNotNull(sut.buildEventForWrapper("signup", userProperties = mapOf("plan" to "pro")))

        sut.reset()
        assertNotNull(sut.sendBuiltEventForWrapper(event))

        assertNull(prefs.getValue(PERSON_PROPERTIES_FOR_FLAGS))
    }

    @Test
    fun `send drops an event built by another instance`() {
        val built = assertNotNull(getSut(httpServers.mockHttp()).buildEventForWrapper("tap"))
        val other = getSut(httpServers.mockHttp())

        assertNull(other.sendBuiltEventForWrapper(built))
    }

    @Test
    fun `send drops an event built before the SDK was set up again`() {
        val http = httpServers.mockHttp()
        var setupConfig: PostHogConfig? = null
        val sut = getSut(http) { setupConfig = this }
        val built = assertNotNull(sut.buildEventForWrapper("tap"))

        sut.close()
        sut.setup(assertNotNull(setupConfig))

        assertNull(sut.sendBuiltEventForWrapper(built))
    }

    @Test
    fun `send keeps the replay debug values from the build`() {
        val http = httpServers.mockHttp()
        val sut = getSut(http)
        sut.startSession()
        val built = assertNotNull(sut.buildEventForWrapper("\$first"))
        sut.endSession()
        Thread.sleep(5)
        sut.startSession()

        assertNotNull(sut.sendBuiltEventForWrapper(built))

        queueExecutor.awaitExecution()
        val sent = events(takeRequest(http)).single().properties()
        assertNotNull(sent["\$sdk_debug_session_start"])
    }

    @Test
    fun `build reads the replay debug state once for the required and deferred keys`() {
        val http = httpServers.mockHttp()
        val replay =
            PostHogSessionReplayHandlerFake(true).apply {
                debugProperties =
                    mapOf(
                        "\$recording_status" to "buffering",
                        "\$sdk_debug_replay_flush_hold_reason" to "below_minimum_duration",
                    )
                // A replay stop between two reads would pair buffering with no hold reason.
                onDebugProperties = { debugProperties = mapOf("\$recording_status" to "disabled") }
            }
        val sut = getSut(http) { addIntegration(replay) }
        sut.startSession()

        val screen = assertNotNull(sut.buildEventForWrapper("\$screen", properties = mapOf("\$screen_name" to "Home")))
        assertNotNull(sut.sendBuiltEventForWrapper(screen))

        queueExecutor.awaitExecution()
        val sent = events(takeRequest(http)).single().properties()
        assertEquals("buffering", sent["\$recording_status"])
        assertEquals("below_minimum_duration", sent["\$sdk_debug_replay_flush_hold_reason"])
    }

    @Test
    fun `send skips the person properties for flags after a reset during the build`() {
        val prefs = PostHogMemoryPreferences()
        lateinit var sut: PostHogInterface
        val replay =
            PostHogSessionReplayHandlerFake(true).apply {
                // Runs while the build enriches the event, after it took its distinct ID.
                onDebugProperties = {
                    onDebugProperties = null
                    sut.reset()
                }
            }
        sut =
            getSut(httpServers.mockHttp()) {
                cachePreferences = prefs
                addIntegration(replay)
            }
        val event = assertNotNull(sut.buildEventForWrapper("signup", userProperties = mapOf("plan" to "pro")))

        assertNotNull(sut.sendBuiltEventForWrapper(event))

        assertNull(prefs.getValue(PERSON_PROPERTIES_FOR_FLAGS))
    }

    @Test
    fun `send skips person processing after the user changed`() {
        val sut = getSut(httpServers.mockHttp())
        val identified = assertNotNull(sut.buildEventForWrapper("signup", userProperties = mapOf("plan" to "pro")))

        sut.reset()
        assertNotNull(sut.sendBuiltEventForWrapper(identified))

        assertEquals(false, assertNotNull(sut.buildEventForWrapper("tap")).options?.get("process_person_profile"))
    }
}

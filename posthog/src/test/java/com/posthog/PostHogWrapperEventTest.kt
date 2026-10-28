package com.posthog

import com.posthog.internal.PostHogMemoryPreferences
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
        val built = assertNotNull(sut.buildEventForWrapper("\$ai_generation", ai = true))

        assertEquals(built.uuid, sut.sendBuiltEventForWrapper(built, ai = true))

        aiExecutor.awaitExecution()
        assertEquals("/i/v1/ai/events", takeRequest(http).path)
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
    fun `build of a screen event caches its name for later events`() {
        val sut = getSut(httpServers.mockHttp())

        val screen = assertNotNull(sut.buildEventForWrapper("\$screen", properties = mapOf("\$screen_name" to " Home ")))
        val next = assertNotNull(sut.buildEventForWrapper("tap"))

        assertEquals("Home", screen.properties?.get("\$screen_name"))
        assertEquals("Home", next.properties?.get("\$screen_name"))
    }
}

package com.posthog

import com.posthog.internal.PostHogMemoryPreferences
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PostHogInteractionGuardTest {
    @get:Rule val directory = TemporaryFolder()
    private val http = MockWebServer().apply { repeat(50) { enqueue(MockResponse().setBody("{}")) } }
    private val executors = List(4) { Executors.newSingleThreadScheduledExecutor() }
    private var client: PostHog? = null
    private lateinit var config: PostHogConfig

    @Suppress("DEPRECATION")
    private fun setup(): PostHog {
        config =
            PostHogConfig(API_KEY, http.url("/").toString()).apply {
                storagePrefix = directory.newFolder().absolutePath
                replayStoragePrefix = directory.newFolder().absolutePath
                cachePreferences = PostHogMemoryPreferences()
                remoteConfig = false
                preloadFeatureFlags = false
                flushAt = 1
            }
        return (PostHog.withInternal(config, executors[0], executors[1], executors[2], executors[3], false) as PostHog).also { client = it }
    }

    private fun drain() {
        executors[0].submit {}.get(5, TimeUnit.SECONDS)
    }

    @AfterTest
    fun cleanup() {
        client?.close()
        executors.forEach { it.shutdownAndAwaitTermination() }
        http.shutdown()
    }

    private fun emit(
        sut: PostHog,
        epoch: Long,
    ) = sut.captureInteraction(
        epoch,
        "\$dead_click",
        mapOf("\$event_type" to "touch", "\$session_id" to sut.getSessionId().toString()),
        Date(1234),
    )

    @Test
    fun `guarded event preserves native context timestamp UUID and runs hooks once`() {
        val sut = setup()
        sut.screen("Checkout")
        drain()
        http.takeRequest(5, TimeUnit.SECONDS)
        var calls = 0
        config.addBeforeSend { event ->
            calls++
            event
        }
        emit(sut, assertNotNull(sut.interactionGeneration()))
        drain()
        assertEquals(1, calls)
        val request = assertNotNull(http.takeRequest(5, TimeUnit.SECONDS))
        val body = request.body.unGzip()
        assertTrue(body.contains("\$dead_click"))
        assertTrue(body.contains("Checkout"))
        assertTrue(body.contains("\$session_id"))
        assertTrue(body.contains("1970-01-01T00:00:01.234"))
        assertTrue(body.contains("uuid"))
    }

    @Test
    fun `old generations cannot survive consent ABA identity reset screen session or close`() {
        val sut = setup()
        config.addBeforeSend { event -> if (event.event == "\$dead_click") event else null }
        val changes: List<() -> Unit> =
            listOf(
                {
                    sut.optOut()
                    sut.optIn()
                },
                {
                    sut.identify("A")
                    sut.reset()
                    sut.identify("B")
                    sut.reset()
                    sut.identify("A")
                },
                { sut.reset() },
                { sut.screen("Next") },
                {
                    sut.endSession()
                    sut.startSession()
                },
                { sut.close() },
            )
        for (change in changes) {
            val before = assertNotNull(sut.interactionGeneration())
            change()
            assertNotEquals(before, sut.interactionGeneration())
            emit(sut, before)
        }
        drain()
        assertEquals(0, http.requestCount)
        assertNull(sut.interactionGeneration())
    }

    @Test
    fun `reentrant beforeSend invalidation drops already prepared event at enqueue boundary`() {
        val sut = setup()
        val changes: List<() -> Unit> =
            listOf(
                {
                    sut.optOut()
                    sut.optIn()
                },
                {
                    sut.reset()
                    sut.identify("A")
                },
                { sut.screen("Next") },
                {
                    sut.endSession()
                    sut.startSession()
                },
            )
        for (change in changes) {
            val hook =
                PostHogBeforeSend { event ->
                    if (event.event == "\$dead_click") {
                        change()
                        event
                    } else {
                        null
                    }
                }
            config.addBeforeSend(hook)
            emit(sut, assertNotNull(sut.interactionGeneration()))
            config.removeBeforeSend(hook)
            assertNotNull(sut.interactionGeneration())
        }
        drain()
        assertEquals(0, http.requestCount)
    }

    @Test
    fun `hook filtering and unsupported event restriction do not affect ordinary capture`() {
        val sut = setup()
        var calls = 0
        config.addBeforeSend {
            calls++
            null
        }
        val epoch = assertNotNull(sut.interactionGeneration())
        sut.captureInteraction(epoch, "other", emptyMap(), Date())
        assertEquals(0, calls)
        emit(sut, epoch)
        assertEquals(1, calls)
        sut.capture("ordinary")
        assertEquals(2, calls)
        drain()
        assertEquals(0, http.requestCount)
    }

    @Test
    fun `nested transition observers cannot begin observation and failed callbacks unwind`() {
        val sut = setup()
        var calls = 0
        config.addIntegration(
            object : PostHogIntegration, PostHogInteractionInvalidationReceiver {
                override fun onInteractionInvalidated() {
                    calls++
                    assertNull(sut.interactionGeneration())
                    if (calls == 1) sut.screen("Nested")
                    throw IllegalStateException("test")
                }
            },
        )
        sut.optOut()
        sut.optIn()
        assertTrue(calls >= 2)
        assertNotNull(sut.interactionGeneration())
    }

    @Test
    fun `concurrent consent transition during hook preparation drops event without blocking host`() {
        val sut = setup()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        config.addBeforeSend { event ->
            if (event.event == "\$dead_click") {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
            }
            event
        }
        val epoch = assertNotNull(sut.interactionGeneration())
        val capture = Thread { emit(sut, epoch) }.apply { start() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        sut.optOut()
        sut.optIn()
        release.countDown()
        capture.join(5000)
        kotlin.test.assertFalse(capture.isAlive)
        drain()
        assertEquals(0, http.requestCount)
    }

    @Test
    fun `beforeSend close invalidates prepared event and throwing hook does not disable observation`() {
        val sut = setup()
        val throwing = PostHogBeforeSend { throw IllegalStateException("test") }
        config.addBeforeSend(throwing)
        emit(sut, assertNotNull(sut.interactionGeneration()))
        config.removeBeforeSend(throwing)
        assertNotNull(sut.interactionGeneration())
        config.addBeforeSend { event ->
            sut.close()
            event
        }
        emit(sut, assertNotNull(sut.interactionGeneration()))
        drain()
        assertEquals(0, http.requestCount)
        assertNull(sut.interactionGeneration())
    }

    @Test
    fun `hook session and timestamp mutations are rejected rather than silently rewritten`() {
        val sut = setup()
        val mutations: List<(PostHogEvent) -> Unit> =
            listOf(
                { it.properties?.set("\$session_id", "other-session") },
                { it.properties?.remove("\$session_id") },
                { it.timestamp.time = 5678 },
            )
        for (mutate in mutations) {
            val hook =
                PostHogBeforeSend { event ->
                    mutate(event)
                    event
                }
            config.addBeforeSend(hook)
            emit(sut, assertNotNull(sut.interactionGeneration()))
            config.removeBeforeSend(hook)
        }
        drain()
        assertEquals(0, http.requestCount)
    }
}

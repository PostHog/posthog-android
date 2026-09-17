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

internal class PostHogCaptureGuardTest {
    @get:Rule val directory = TemporaryFolder()
    private val http = MockWebServer().apply { repeat(50) { enqueue(MockResponse().setBody("{}")) } }
    private val executors = List(4) { Executors.newSingleThreadScheduledExecutor() }
    private var client: PostHog? = null
    private lateinit var config: PostHogConfig
    private val guard = PostHogCaptureGuard()
    private val integration =
        object : PostHogIntegration {
            override fun install(postHog: PostHogInterface) = guard.setActive(true)

            override fun uninstall() = guard.setActive(false)

            override fun onChange() = guard.invalidate()
        }

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
                addIntegration(integration)
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
    ) = sut.captureGuarded(
        guard,
        epoch,
        "deferred",
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
        emit(sut, assertNotNull(guard.generation()))
        drain()
        assertEquals(1, calls)
        val request = assertNotNull(http.takeRequest(5, TimeUnit.SECONDS))
        val body = request.body.unGzip()
        assertTrue(body.contains("deferred"))
        assertTrue(body.contains("Checkout"))
        assertTrue(body.contains("\$session_id"))
        assertTrue(body.contains("1970-01-01T00:00:01.234"))
        assertTrue(body.contains("uuid"))
    }

    @Test
    fun `old generations cannot survive consent ABA identity reset screen session or close`() {
        val sut = setup()
        config.addBeforeSend { event -> if (event.event == "deferred") event else null }
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
            val before = assertNotNull(guard.generation())
            change()
            assertNotEquals(before, guard.generation())
            emit(sut, before)
        }
        drain()
        assertEquals(0, http.requestCount)
        assertNull(guard.generation())
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
                    if (event.event == "deferred") {
                        change()
                        event
                    } else {
                        null
                    }
                }
            config.addBeforeSend(hook)
            emit(sut, assertNotNull(guard.generation()))
            config.removeBeforeSend(hook)
            assertNotNull(guard.generation())
        }
        drain()
        assertEquals(0, http.requestCount)
    }

    @Test
    fun `guarded capture supports generic events without changing ordinary capture`() {
        val sut = setup()
        var calls = 0
        config.addBeforeSend {
            calls++
            null
        }
        val epoch = assertNotNull(guard.generation())
        sut.captureGuarded(guard, epoch, "other", mapOf("\$session_id" to sut.getSessionId().toString()), Date())
        assertEquals(1, calls)
        emit(sut, epoch)
        assertEquals(2, calls)
        sut.capture("ordinary")
        assertEquals(3, calls)
        drain()
        assertEquals(0, http.requestCount)
    }

    @Test
    fun `one-shot notifications invalidate without pausing and callback failures cannot block consent`() {
        val sut = setup()
        val before = assertNotNull(guard.generation())
        val generations = mutableListOf<Long?>()
        var calls = 0
        config.addIntegration(
            object : PostHogIntegration {
                override fun onChange() {
                    calls++
                    generations += guard.generation()
                    if (calls == 1) sut.screen("Nested")
                    throw IllegalStateException("test")
                }
            },
        )
        sut.optOut()
        assertTrue(sut.isOptOut())
        assertEquals(2, calls)
        assertTrue(generations.all { it != null && it != before })
        sut.optIn()
        assertNotNull(guard.generation())
    }

    @Test
    fun `concurrent consent transition during hook preparation drops event without blocking host`() {
        val sut = setup()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        config.addBeforeSend { event ->
            if (event.event == "deferred") {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
            }
            event
        }
        val epoch = assertNotNull(guard.generation())
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
        emit(sut, assertNotNull(guard.generation()))
        config.removeBeforeSend(throwing)
        assertNotNull(guard.generation())
        config.addBeforeSend { event ->
            sut.close()
            event
        }
        emit(sut, assertNotNull(guard.generation()))
        drain()
        assertEquals(0, http.requestCount)
        assertNull(guard.generation())
    }

    @Test
    fun `close notifies once and uninstalls even when callbacks throw`() {
        val sut = setup()
        var changes = 0
        var uninstalls = 0
        config.addIntegration(
            object : PostHogIntegration {
                override fun onChange() {
                    changes++
                    throw IllegalStateException("test")
                }

                override fun uninstall() {
                    uninstalls++
                }
            },
        )
        sut.close()
        assertEquals(1, changes)
        assertEquals(1, uninstalls)
        assertNull(guard.generation())
    }

    @Test
    fun `uninstall independently invalidates pending observations`() {
        val sut = setup()
        val before = assertNotNull(guard.generation())
        integration.uninstall()
        assertNull(guard.generation())
        integration.install(sut)
        assertNotEquals(before, guard.generation())
        emit(sut, before)
        drain()
        assertEquals(0, http.requestCount)
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
            emit(sut, assertNotNull(guard.generation()))
            config.removeBeforeSend(hook)
        }
        drain()
        assertEquals(0, http.requestCount)
    }
}

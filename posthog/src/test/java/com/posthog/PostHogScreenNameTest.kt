package com.posthog

import com.posthog.internal.PostHogMemoryPreferences
import com.posthog.internal.PostHogThreadFactory
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

internal class PostHogScreenNameTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    private val queueExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestQueue"))
    private val replayQueueExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestReplayQueue"))
    private val remoteConfigExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestRemoteConfig"))
    private val cachedEventsExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestCachedEvents"))
    private lateinit var config: PostHogConfig

    private val captured = mutableListOf<PostHogEvent>()

    @Suppress("DEPRECATION")
    private fun getSut(): PostHogInterface {
        val storage = tmpDir.newFolder().absolutePath
        config =
            PostHogConfig(API_KEY, "http://localhost").apply {
                flushAt = 1
                storagePrefix = File(storage, "events").absolutePath
                replayStoragePrefix = File(storage, "snapshots").absolutePath
                preloadFeatureFlags = false
                cachePreferences = PostHogMemoryPreferences()
                addBeforeSend(
                    PostHogBeforeSend { event ->
                        captured.add(event)
                        null
                    },
                )
            }
        return PostHog.withInternal(
            config,
            queueExecutor,
            replayQueueExecutor,
            remoteConfigExecutor,
            cachedEventsExecutor,
            reloadFeatureFlags = true,
        )
    }

    @AfterTest
    fun `set down`() {
        tmpDir.root.deleteRecursively()
    }

    private fun PostHogInterface.captureAndAwait(
        event: String,
        properties: Map<String, Any>? = null,
    ) {
        capture(event, DISTINCT_ID, properties = properties)
        queueExecutor.awaitExecution()
    }

    @Test
    fun `event captured before screen has no screen_name`() {
        val sut = getSut()

        sut.captureAndAwait(EVENT)

        val theEvent = captured.first { it.event == EVENT }
        assertFalse(theEvent.properties!!.containsKey("\$screen_name"))

        sut.close()
    }

    @Test
    fun `event captured after screen carries screen_name`() {
        val sut = getSut()

        sut.screen("Home")
        sut.captureAndAwait(EVENT)

        val theEvent = captured.first { it.event == EVENT }
        assertEquals("Home", theEvent.properties!!["\$screen_name"])

        sut.close()
    }

    @Test
    fun `caller-supplied screen_name overrides cached value`() {
        val sut = getSut()

        sut.screen("Home")
        sut.captureAndAwait(EVENT, properties = mapOf("\$screen_name" to "Override"))

        val theEvent = captured.first { it.event == EVENT }
        assertEquals("Override", theEvent.properties!!["\$screen_name"])

        sut.close()
    }

    @Test
    fun `caller-supplied screen_name from posthog-flutter is preserved`() {
        val sut = getSut()

        sut.screen("AndroidScreen")
        sut.captureAndAwait(EVENT, properties = mapOf("\$screen_name" to "FlutterHome"))

        val theEvent = captured.first { it.event == EVENT }
        assertEquals("FlutterHome", theEvent.properties!!["\$screen_name"])

        sut.close()
    }

    @Test
    fun `screen with whitespace-padded title is trimmed for cache and event payload`() {
        val sut = getSut()

        sut.screen("  Home  ")
        sut.captureAndAwait(EVENT)

        val screenEvent = captured.first { it.event == PostHogEventName.SCREEN.event }
        assertEquals("Home", screenEvent.properties!!["\$screen_name"])

        val theEvent = captured.first { it.event == EVENT }
        assertEquals("Home", theEvent.properties!!["\$screen_name"])

        sut.close()
    }

    @Test
    fun `screen with blank title is dropped and does not touch the cache`() {
        val sut = getSut()

        sut.screen("Home")
        sut.screen("")
        sut.screen("   ")
        sut.captureAndAwait(EVENT)

        // Blank screen() calls leave the cache intact at the last useful name.
        val theEvent = captured.first { it.event == EVENT }
        assertEquals("Home", theEvent.properties!!["\$screen_name"])
        assertFalse(
            captured.any { it.event == PostHogEventName.SCREEN.event && (it.properties?.get("\$screen_name") as? String).isNullOrBlank() },
        )

        sut.close()
    }

    @Test
    fun `caller-supplied empty screen_name ships verbatim as explicit unset`() {
        // Caller passing $screen_name = "" is treated as intentional unset for
        // that event (e.g. a modal or a screen that doesn't belong to the
        // cached context). Cache is NOT used as a fallback when the key is
        // explicitly present in the caller's properties.
        val sut = getSut()

        sut.screen("Home")
        sut.captureAndAwait(EVENT, properties = mapOf("\$screen_name" to ""))

        val theEvent = captured.first { it.event == EVENT }
        assertEquals("", theEvent.properties!!["\$screen_name"])

        sut.close()
    }

    @Test
    fun `caller-supplied whitespace screen_name ships verbatim`() {
        // Caller's value goes through putAll without transformation — if a
        // caller passes "   " they get "   " on the wire. They opted in by
        // passing the key.
        val sut = getSut()

        sut.screen("Home")
        sut.captureAndAwait(EVENT, properties = mapOf("\$screen_name" to "   "))

        val theEvent = captured.first { it.event == EVENT }
        assertEquals("   ", theEvent.properties!!["\$screen_name"])

        sut.close()
    }

    @Test
    fun `reset clears screen_name from subsequent events`() {
        val sut = getSut()

        sut.screen("Home")
        sut.reset()
        sut.captureAndAwait(EVENT)

        val theEvent = captured.first { it.event == EVENT }
        assertFalse(theEvent.properties!!.containsKey("\$screen_name"))

        sut.close()
    }

    @Test
    fun `exception event carries screen_name`() {
        val sut = getSut()

        sut.screen("Home")
        sut.captureException(RuntimeException("boom"))
        queueExecutor.awaitExecution()

        val theEvent = captured.first { it.event == "\$exception" }
        assertEquals("Home", theEvent.properties!!["\$screen_name"])

        sut.close()
    }

    @Test
    fun `snapshot event does not carry screen_name`() {
        val sut = getSut()

        sut.screen("Home")
        sut.capture("\$snapshot", DISTINCT_ID, properties = mapOf("\$session_id" to "test-session-id"))
        queueExecutor.awaitExecution()

        val theEvent = captured.first { it.event == "\$snapshot" }
        assertFalse(theEvent.properties!!.containsKey("\$screen_name"))

        sut.close()
    }
}

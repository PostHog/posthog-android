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
    fun `reset clears screen_name from subsequent events`() {
        val sut = getSut()

        sut.screen("Home")
        sut.reset()
        sut.captureAndAwait(EVENT)

        val theEvent = captured.first { it.event == EVENT }
        assertFalse(theEvent.properties!!.containsKey("\$screen_name"))

        sut.close()
    }
}

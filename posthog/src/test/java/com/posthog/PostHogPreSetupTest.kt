package com.posthog

import com.posthog.internal.PostHogBatchEvent
import com.posthog.internal.PostHogMemoryPreferences
import com.posthog.internal.PostHogPreferences.Companion.GROUPS
import com.posthog.internal.PostHogSerializer
import com.posthog.internal.PostHogThreadFactory
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class PostHogPreSetupTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    private val queueExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestQueue"))
    private val replayQueueExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestReplayQueue"))
    private val remoteConfigExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestRemoteConfig"))
    private val cachedEventsExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestCachedEvents"))
    private val serializer = PostHogSerializer(PostHogConfig(API_KEY))
    private val preferences = PostHogMemoryPreferences()

    private fun getConfig(host: String): PostHogConfig {
        val storagePrefix = tmpDir.newFolder().absolutePath
        return PostHogConfig(API_KEY, host).apply {
            flushAt = 1
            this.storagePrefix = File(storagePrefix, "events").absolutePath
            this.replayStoragePrefix = File(storagePrefix, "snapshots").absolutePath
            this.logsStoragePrefix = File(storagePrefix, "logs").absolutePath
            preloadFeatureFlags = false
            cachePreferences = preferences
        }
    }

    private fun getNotSetUpSut(): PostHogInterface =
        PostHog.newInstanceInternal(
            queueExecutor,
            replayQueueExecutor,
            remoteConfigExecutor,
            cachedEventsExecutor,
            reloadFeatureFlags = false,
        )

    @AfterTest
    fun `set down`() {
        tmpDir.root.deleteRecursively()
    }

    @Test
    fun `capture made before setup is replayed after setup`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getNotSetUpSut()

        sut.capture(EVENT, DISTINCT_ID, props)

        assertEquals(0, http.requestCount)

        sut.setup(getConfig(url.toString()))

        queueExecutor.shutdownAndAwaitTermination()

        val batch = serializer.deserialize<PostHogBatchEvent>(http.takeRequest().body.unGzip().reader())
        val theEvent = batch.batch.first()
        assertEquals(EVENT, theEvent.event)
        assertEquals(DISTINCT_ID, theEvent.distinctId)
        assertEquals("value", theEvent.properties!!["prop"] as String)

        sut.close()
    }

    @Test
    fun `replayed capture keeps the time the call was made`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getNotSetUpSut()

        val before = System.currentTimeMillis()
        sut.capture(EVENT, DISTINCT_ID, props)
        val after = System.currentTimeMillis()

        Thread.sleep(50)
        sut.setup(getConfig(url.toString()))

        queueExecutor.shutdownAndAwaitTermination()

        val batch = serializer.deserialize<PostHogBatchEvent>(http.takeRequest().body.unGzip().reader())
        val timestamp = assertNotNull(batch.batch.first().timestamp).time
        assertTrue(timestamp in before..after, "expected $timestamp within $before..$after")

        sut.close()
    }

    @Test
    fun `screen made before setup is replayed after setup`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getNotSetUpSut()

        sut.screen("Home")

        sut.setup(getConfig(url.toString()))

        queueExecutor.shutdownAndAwaitTermination()

        val batch = serializer.deserialize<PostHogBatchEvent>(http.takeRequest().body.unGzip().reader())
        val theEvent = batch.batch.first()
        assertEquals("\$screen", theEvent.event)
        assertEquals("Home", theEvent.properties!!["\$screen_name"])

        sut.close()
    }

    @Test
    fun `replayed screen keeps the time the call was made`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getNotSetUpSut()

        val before = System.currentTimeMillis()
        sut.screen("Home")
        val after = System.currentTimeMillis()

        Thread.sleep(50)
        sut.setup(getConfig(url.toString()))

        queueExecutor.shutdownAndAwaitTermination()

        val batch = serializer.deserialize<PostHogBatchEvent>(http.takeRequest().body.unGzip().reader())
        val timestamp = assertNotNull(batch.batch.first().timestamp).time
        assertTrue(timestamp in before..after, "expected $timestamp within $before..$after")

        sut.close()
    }

    @Test
    fun `screen made before setup does not name the events captured before it`() {
        val http = mockHttp()
        val url = http.url("/")

        val captured = mutableListOf<PostHogEvent>()
        val config =
            getConfig(url.toString()).apply {
                addBeforeSend(
                    PostHogBeforeSend { event ->
                        captured.add(event)
                        null
                    },
                )
            }

        val sut = getNotSetUpSut()

        sut.capture("before")
        sut.screen("Home")
        sut.capture("after")

        sut.setup(config)

        queueExecutor.shutdownAndAwaitTermination()

        assertFalse(captured.first { it.event == "before" }.properties!!.containsKey("\$screen_name"))
        assertEquals("Home", captured.first { it.event == "\$screen" }.properties!!["\$screen_name"])
        assertEquals("Home", captured.first { it.event == "after" }.properties!!["\$screen_name"])

        sut.close()
    }

    @Test
    fun `the last screen made before setup names only the events that follow it`() {
        val http = mockHttp()
        val url = http.url("/")

        val captured = mutableListOf<PostHogEvent>()
        val config =
            getConfig(url.toString()).apply {
                addBeforeSend(
                    PostHogBeforeSend { event ->
                        captured.add(event)
                        null
                    },
                )
            }

        val sut = getNotSetUpSut()

        sut.screen("Home")
        sut.capture("onHome")
        sut.screen("Settings")
        sut.capture("onSettings")

        sut.setup(config)

        queueExecutor.shutdownAndAwaitTermination()

        assertEquals("Home", captured.first { it.event == "onHome" }.properties!!["\$screen_name"])
        assertEquals("Settings", captured.first { it.event == "onSettings" }.properties!!["\$screen_name"])

        sut.close()
    }

    @Test
    fun `identify made before setup is replayed after setup`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getNotSetUpSut()

        sut.identify(DISTINCT_ID, userProperties = userProps)

        sut.setup(getConfig(url.toString()))

        queueExecutor.shutdownAndAwaitTermination()

        val batch = serializer.deserialize<PostHogBatchEvent>(http.takeRequest().body.unGzip().reader())
        val theEvent = batch.batch.first()
        assertEquals("\$identify", theEvent.event)
        assertEquals(DISTINCT_ID, theEvent.distinctId)
        assertEquals(userProps, theEvent.properties!!["\$set"])
        assertEquals(DISTINCT_ID, sut.distinctId())

        sut.close()
    }

    @Test
    fun `register made before setup is persisted after setup`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getNotSetUpSut()

        sut.register("prop", "value")

        assertEquals(null, preferences.getValue("prop"))

        sut.setup(getConfig(url.toString()))

        assertEquals("value", preferences.getValue("prop"))

        sut.close()
    }

    @Test
    fun `reserved register keys are rejected instead of buffered`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getNotSetUpSut()

        sut.register(GROUPS, "value")

        sut.setup(getConfig(url.toString()))

        assertEquals(null, preferences.getValue(GROUPS))

        sut.close()
    }
}

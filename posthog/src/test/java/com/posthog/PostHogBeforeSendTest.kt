package com.posthog

import com.posthog.internal.PostHogBatchEvent
import com.posthog.internal.PostHogMemoryPreferences
import com.posthog.internal.PostHogSerializer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

internal class PostHogBeforeSendTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    private val serializer = PostHogSerializer(PostHogConfig(API_KEY))

    @Suppress("DEPRECATION")
    private fun captureWithHooks(
        hooks: List<PostHogBeforeSend>,
        trigger: (PostHogInterface) -> Unit = { it.getFeatureFlag("key") },
    ): List<RecordedRequest> {
        val http = mockHttp()
        val queueExecutor = Executors.newSingleThreadScheduledExecutor()
        val replayExecutor = Executors.newSingleThreadScheduledExecutor()
        val remoteExecutor = Executors.newSingleThreadScheduledExecutor()
        val cachedExecutor = Executors.newSingleThreadScheduledExecutor()
        val config =
            PostHogConfig(API_KEY, http.url("/").toString()).apply {
                flushAt = 1
                storagePrefix = tmpDir.newFolder().absolutePath
                replayStoragePrefix = tmpDir.newFolder().absolutePath
                preloadFeatureFlags = false
                remoteConfig = false
                cachePreferences = PostHogMemoryPreferences()
                hooks.forEach { addBeforeSend(it) }
            }
        val sut = PostHog.withInternal(config, queueExecutor, replayExecutor, remoteExecutor, cachedExecutor, false)
        try {
            trigger(sut)
            sut.flush()
            queueExecutor.shutdownAndAwaitTermination()
            replayExecutor.shutdownAndAwaitTermination()
            return List(http.requestCount) { assertNotNull(http.takeRequest(5, TimeUnit.SECONDS)) }
        } finally {
            sut.close()
            listOf(queueExecutor, replayExecutor, remoteExecutor, cachedExecutor).forEach { it.shutdownAndAwaitTermination() }
            http.shutdown()
        }
    }

    @Test
    fun `drop events`() {
        val cases =
            listOf<Pair<String, (PostHogInterface) -> Unit>>(
                "test_event" to { it.capture("test_event") },
                PostHogEventName.SCREEN.event to { it.screen("screen") },
                PostHogEventName.SNAPSHOT.event to {
                    it.capture(PostHogEventName.SNAPSHOT.event, properties = mapOf("\$session_id" to uuid.toString()))
                },
                PostHogEventName.IDENTIFY.event to { it.identify("user_id") },
                PostHogEventName.GROUP_IDENTIFY.event to { it.group("type", "key") },
                PostHogEventName.CREATE_ALIAS.event to { it.alias("alias") },
                PostHogEventName.FEATURE_FLAG_CALLED.event to { it.getFeatureFlag("key") },
            )
        for ((eventName, trigger) in cases) {
            for (drop in listOf(false, true)) {
                val seen = mutableListOf<String>()
                val requests =
                    captureWithHooks(
                        listOf(
                            PostHogBeforeSend { event ->
                                seen.add(event.event)
                                if (drop) null else event
                            },
                        ),
                        trigger,
                    )
                assertEquals(setOf(eventName), seen.toSet(), "hook invocation for $eventName, drop=$drop")
                assertEquals(if (drop) 0 else 1, requests.size, "delivery for $eventName, drop=$drop")
            }
        }
    }

    @Test
    fun `sends the event returned by a copy hook`() {
        val requests =
            captureWithHooks(
                listOf(PostHogBeforeSend { it.copy(event = PostHogEventName.SCREEN.event) }),
            )
        assertEquals(1, requests.size)
        val batch = serializer.deserialize<PostHogBatchEvent>(requests.single().body.unGzip().reader())
        assertEquals(listOf(PostHogEventName.SCREEN.event), batch.batch.map { it.event })
    }

    @Test
    fun `mutate event properties`() {
        val requests =
            captureWithHooks(
                listOf(
                    PostHogBeforeSend { event ->
                        event.properties?.set("key", "value")
                        event
                    },
                ),
            )
        assertEquals(1, requests.size)
        val batch = serializer.deserialize<PostHogBatchEvent>(requests.single().body.unGzip().reader())
        assertEquals("value", batch.batch.single().properties?.get("key"))
    }

    @Test
    fun `chains multiple hooks, each receiving the previous hook's output`() {
        val requests =
            captureWithHooks(
                listOf(
                    PostHogBeforeSend { event ->
                        event.copy(properties = event.properties?.toMutableMap()?.apply { set("first", "1") })
                    },
                    PostHogBeforeSend { event ->
                        event.properties?.set("sawFirst", event.properties?.get("first").toString())
                        event
                    },
                ),
            )
        assertEquals(1, requests.size)
        val batch = serializer.deserialize<PostHogBatchEvent>(requests.single().body.unGzip().reader())
        val event = batch.batch.single()
        assertEquals("1", event.properties?.get("first"))
        assertEquals("1", event.properties?.get("sawFirst"))
    }

    @Test
    fun `drops the event when a hook throws`() {
        var callsAfterThrow = 0
        val requests =
            captureWithHooks(
                listOf(
                    PostHogBeforeSend { event ->
                        event.properties?.set("key", "value")
                        event
                    },
                    PostHogBeforeSend { throw RuntimeException("boom") },
                    PostHogBeforeSend {
                        callsAfterThrow++
                        it
                    },
                ),
            )
        assertEquals(0, requests.size)
        assertEquals(0, callsAfterThrow)
    }
}

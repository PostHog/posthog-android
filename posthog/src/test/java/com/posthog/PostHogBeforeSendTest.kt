package com.posthog

import com.posthog.internal.PostHogBatchEvent
import com.posthog.internal.PostHogMemoryPreferences
import com.posthog.internal.PostHogSerializer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.util.TreeMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class PostHogBeforeSendTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    private val serializer = PostHogSerializer(PostHogConfig(PROJECT_TOKEN))

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
            PostHogConfig(PROJECT_TOKEN, http.url("/").toString()).apply {
                flushAt = 1
                storagePrefix = tmpDir.newFolder().absolutePath
                replayStoragePrefix = tmpDir.newFolder().absolutePath
                preloadFeatureFlags = false
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
    fun `a hook's edits to nested values reach the wire but not the caller's objects`() {
        val nested = mutableMapOf<String, Any>("kept" to mutableListOf("a"))
        val requests =
            captureWithHooks(
                listOf(
                    PostHogBeforeSend { event ->
                        @Suppress("UNCHECKED_CAST")
                        val map = event.properties?.get("nested") as MutableMap<String, Any>
                        map["hook"] = true
                        @Suppress("UNCHECKED_CAST")
                        (map["kept"] as MutableList<String>).add("b")
                        event
                    },
                ),
                trigger = { it.capture("event", properties = mapOf("nested" to nested)) },
            )

        assertEquals(mutableMapOf<String, Any>("kept" to mutableListOf("a")), nested)
        val sent = requests.single().body.unGzip()
        assertTrue(sent.contains("\"hook\":true"), sent)
        assertTrue(sent.contains("\"kept\":[\"a\",\"b\"]"), sent)
    }

    @Test
    fun `a hook sees arrays as arrays, and its edits don't reach the caller's objects`() {
        val nested = mutableMapOf<String, Any>("kept" to true)
        val requests =
            captureWithHooks(
                listOf(
                    PostHogBeforeSend { event ->
                        val items = event.properties?.get("items") as Array<*>
                        @Suppress("UNCHECKED_CAST")
                        (items[0] as MutableMap<String, Any>)["hook"] = true
                        assertEquals("a", (event.properties?.get("names") as Array<*>)[0])
                        event
                    },
                ),
                trigger = { it.capture("event", properties = mapOf("items" to arrayOf(nested), "names" to arrayOf("a"))) },
            )

        assertEquals(mutableMapOf<String, Any>("kept" to true), nested)
        assertEquals(1, requests.size)
    }

    @Test
    fun `a hook's edit to a primitive array doesn't reach the caller's array`() {
        val numbers = intArrayOf(1)
        val requests =
            captureWithHooks(
                listOf(
                    PostHogBeforeSend { event ->
                        (event.properties?.get("numbers") as IntArray)[0] = 2
                        event
                    },
                ),
                trigger = { it.capture("event", properties = mapOf("numbers" to numbers)) },
            )

        assertEquals(1, numbers[0])
        assertEquals(1, requests.size)
    }

    @Test
    fun `a hook's lookup in a sorted map uses the map's comparator`() {
        val headers = TreeMap<String, Any>(String.CASE_INSENSITIVE_ORDER)
        headers["Authorization"] = "secret"
        val requests =
            captureWithHooks(
                listOf(
                    PostHogBeforeSend { event ->
                        @Suppress("UNCHECKED_CAST")
                        (event.properties?.get("headers") as MutableMap<String, Any>).remove("authorization")
                        event
                    },
                ),
                trigger = { it.capture("event", properties = mapOf("headers" to headers)) },
            )

        assertEquals("secret", headers["Authorization"])
        assertTrue(!requests.single().body.unGzip().contains("secret"))
    }

    @Test
    fun `a hook can remove a self-referencing property`() {
        val cycle = mutableMapOf<String, Any>()
        cycle["self"] = cycle
        val requests =
            captureWithHooks(
                listOf(
                    PostHogBeforeSend { event ->
                        event.properties?.remove("cycle")
                        event
                    },
                ),
                trigger = { it.capture("event", properties = mapOf("cycle" to cycle)) },
            )

        assertEquals(1, requests.size)
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

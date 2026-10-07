package com.posthog.internal

import com.posthog.API_KEY
import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import com.posthog.TestHttpServers
import com.posthog.shutdownAndAwaitTermination
import com.posthog.unGzip
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class PostHogReplayQueueTest {
    @get:Rule
    val httpServers = TestHttpServers()

    @get:Rule
    val tmpDir = TemporaryFolder()

    private val executor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("ReplayQueueTest"))
    private val queues = mutableListOf<PostHogQueue<PostHogEvent>>()
    private val clock =
        FakePostHogDateProvider().apply {
            setCurrentDate(Date(0))
            setAddSecondsToCurrentDate(Date(1000))
        }

    @AfterTest
    fun cleanup() {
        queues.forEach {
            it.stop()
            it.clear()
        }
        executor.shutdownAndAwaitTermination()
    }

    private fun awaitQueue() {
        executor.submit {}.get(60, TimeUnit.SECONDS)
    }

    private fun queue(
        http: MockWebServer,
        path: String = tmpDir.newFolder().absolutePath,
        flushAt: Int = 100,
        maxBatchSize: Int = 50,
        replay: Boolean = true,
    ): PostHogQueue<PostHogEvent> {
        val config =
            PostHogConfig(API_KEY, http.url("/").toString()).apply {
                this.flushAt = flushAt
                this.maxBatchSize = maxBatchSize
                dateProvider = clock
            }
        val api = PostHogApi(config)
        val spec = if (replay) EndpointSpec.snapshot(config, api, path) else EndpointSpec.batch(config, api, path)
        return PostHogQueue(config, spec, executor).also { queues.add(it) }
    }

    private fun snapshot(
        session: String,
        distinctId: String = "user",
        marker: String = session,
    ): PostHogEvent =
        PostHogEvent(
            "\$snapshot",
            distinctId,
            mutableMapOf(
                "\$session_id" to session,
                "distinct_id" to distinctId,
                "\$snapshot_source" to "mobile",
                "\$snapshot_data" to listOf(mapOf("marker" to marker)),
            ),
        )

    private fun requests(
        http: MockWebServer,
        count: Int = http.requestCount,
    ): List<List<PostHogEvent>> {
        val serializer = PostHogSerializer(PostHogConfig(API_KEY))
        return List(count) {
            val request = assertNotNull(http.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("/s/", request.path)
            assertEquals("gzip", request.getHeader("Content-Encoding"))
            serializer.deserialize<List<PostHogEvent>>(request.body.unGzip().reader())
        }
    }

    private fun assertBatches(
        http: MockWebServer,
        expected: List<List<Pair<String, String>>>,
    ) {
        val batches = requests(http, expected.size)
        assertEquals(expected, batches.map { batch -> batch.map { it.properties!!["\$session_id"] as String to it.distinctId } })
    }

    @Test
    fun `session boundaries split requests in FIFO order`() {
        val http = httpServers.mockHttp(total = 3)
        val sut = queue(http)
        listOf("old", "old", "new", "new", "old").forEach { sut.add(snapshot(it)) }
        sut.flush()
        awaitQueue()

        assertBatches(http, listOf(List(2) { "old" to "user" }, List(2) { "new" to "user" }, listOf("old" to "user")))
        assertEquals(0, sut.size)
    }

    @Test
    fun `distinct ID boundaries split requests within the same session`() {
        val http = httpServers.mockHttp(total = 2)
        val sut = queue(http)
        listOf("anonymous", "anonymous", "identified", "identified").forEach { sut.add(snapshot("session", it)) }
        sut.flush()
        awaitQueue()

        assertBatches(http, listOf(List(2) { "session" to "anonymous" }, List(2) { "session" to "identified" }))
        assertEquals(0, sut.size)
    }

    @Test
    fun `same session and identity remain batched up to the cap`() {
        val http = httpServers.mockHttp(total = 3)
        val sut = queue(http, maxBatchSize = 2)
        repeat(5) { sut.add(snapshot("session", marker = "$it")) }
        sut.flush()
        awaitQueue()

        val batches = requests(http)
        assertEquals(listOf(2, 2, 1), batches.map { it.size })
        assertEquals(
            listOf("0", "1", "2", "3", "4"),
            batches.flatten().map {
                ((it.properties!!["\$snapshot_data"] as List<*>).single() as Map<*, *>)["marker"]
            },
        )
    }

    @Test
    fun `threshold flush sends all boundary groups within its original cap`() {
        val http = httpServers.mockHttp(total = 3)
        val sut = queue(http, flushAt = 6, maxBatchSize = 4)
        listOf("first", "first", "second", "second", "later", "later").forEach { sut.add(snapshot(it)) }
        awaitQueue()

        assertBatches(http, listOf(List(2) { "first" to "user" }, List(2) { "second" to "user" }))
        assertEquals(2, sut.size)
        sut.flush()
        awaitQueue()
        assertBatches(http, listOf(List(2) { "later" to "user" }))
        assertEquals(0, sut.size)
    }

    @Test
    fun `persisted old session stays separate from new session after restart`() {
        val http = httpServers.mockHttp(total = 2)
        val path = tmpDir.newFolder().absolutePath
        val original = queue(http, path)
        repeat(2) { original.add(snapshot("old")) }
        awaitQueue()
        original.dequeList.forEachIndexed { index, file -> assertTrue(file.setLastModified(1000L * (index + 1))) }
        original.stop()

        val restarted = queue(http, path, flushAt = 4)
        repeat(2) { restarted.add(snapshot("new")) }
        awaitQueue()

        assertBatches(http, listOf(List(2) { "old" to "user" }, List(2) { "new" to "user" }))
        assertEquals(0, restarted.size)
    }

    @Test
    fun `retryable failure retains the failed group and later groups`() {
        val http = httpServers.create()
        http.enqueue(MockResponse())
        http.enqueue(MockResponse().setResponseCode(503))
        val sut = queue(http)
        listOf("sent", "failed", "failed", "later").forEach { sut.add(snapshot(it)) }
        awaitQueue()
        val files = sut.dequeList
        sut.flush()
        awaitQueue()

        assertBatches(http, listOf(listOf("sent" to "user"), List(2) { "failed" to "user" }))
        assertFalse(files.first().exists())
        assertEquals(files.drop(1), sut.dequeList)
        assertTrue(files.drop(1).all { it.exists() })
        assertEquals(1, sut.currentRetryCountForTesting)
        sut.flush()
        awaitQueue()
        assertEquals(2, http.requestCount)

        http.enqueue(MockResponse())
        http.enqueue(MockResponse())
        clock.setCurrentDate(Date(1000))
        sut.flush()
        awaitQueue()
        assertBatches(http, listOf(List(2) { "failed" to "user" }, listOf("later" to "user")))
        assertEquals(0, sut.size)
        assertEquals(0, sut.currentRetryCountForTesting)
    }

    @Test
    fun `terminal response deletes only the rejected group and sends later groups`() {
        val http = httpServers.create()
        http.enqueue(MockResponse().setResponseCode(400))
        http.enqueue(MockResponse())
        val sut = queue(http)
        listOf("rejected", "rejected", "later").forEach { sut.add(snapshot(it)) }
        sut.flush()
        awaitQueue()

        assertBatches(http, listOf(List(2) { "rejected" to "user" }, listOf("later" to "user")))
        assertEquals(0, sut.size)
    }

    @Test
    fun `oversized group reduces cap without deleting later groups`() {
        val http = httpServers.mockHttp(response = MockResponse().setResponseCode(413))
        val sut = queue(http, maxBatchSize = 4)
        listOf("oversized", "oversized", "later").forEach { sut.add(snapshot(it)) }
        awaitQueue()
        val files = sut.dequeList
        sut.flush()
        awaitQueue()

        assertBatches(http, listOf(List(2) { "oversized" to "user" }))
        assertEquals(files, sut.dequeList)
        assertEquals(1, sut.currentBatchCapForTesting)
        repeat(3) { http.enqueue(MockResponse()) }
        clock.setCurrentDate(Date(1000))
        sut.flush()
        awaitQueue()
        assertBatches(http, listOf(listOf("oversized" to "user"), listOf("oversized" to "user"), listOf("later" to "user")))
        assertEquals(0, sut.size)
    }

    @Test
    fun `corrupt cached entry does not merge session boundaries`() {
        val http = httpServers.mockHttp(total = 2)
        val sut = queue(http)
        listOf("old", "corrupt", "new").forEach { sut.add(snapshot(it)) }
        awaitQueue()
        val corruptFile = sut.dequeList[1]
        corruptFile.writeText("invalid json")
        sut.flush()
        awaitQueue()

        assertBatches(http, listOf(listOf("old" to "user"), listOf("new" to "user")))
        assertFalse(corruptFile.exists())
        assertEquals(0, sut.size)
    }

    @Test
    fun `analytics batches still span sessions and identities`() {
        val http = httpServers.mockHttp()
        val sut = queue(http, replay = false)
        sut.add(snapshot("first", "anonymous").copy(event = "event"))
        sut.add(snapshot("second", "identified").copy(event = "event"))
        sut.flush()
        awaitQueue()

        assertEquals(1, http.requestCount)
        val request = assertNotNull(http.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/batch", request.path)
        val payload = PostHogSerializer(PostHogConfig(API_KEY)).deserialize<Map<String, Any>>(request.body.unGzip().reader())
        assertEquals(2, (payload["batch"] as List<*>).size)
        assertEquals(0, sut.size)
    }
}

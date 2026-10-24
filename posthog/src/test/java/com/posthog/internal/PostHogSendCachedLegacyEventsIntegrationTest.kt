package com.posthog.internal

import com.posthog.PROJECT_TOKEN
import com.posthog.PostHogConfig
import com.posthog.TestHttpServers
import com.posthog.shutdownAndAwaitTermination
import com.posthog.unGzip
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

internal class PostHogSendCachedLegacyEventsIntegrationTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    @get:Rule
    val servers = TestHttpServers()

    private val executor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("Test"))
    private val integrations = mutableListOf<PostHogSendCachedEventsIntegration>()
    private val event = File("src/test/resources/json/basic-event.json").readText()

    private fun getSut(
        legacyStoragePrefix: String,
        host: String,
        maxBatchSize: Int = 50,
        networkStatus: PostHogNetworkStatus? = null,
    ): PostHogSendCachedEventsIntegration {
        val config =
            PostHogConfig(PROJECT_TOKEN, host).apply {
                this.legacyStoragePrefix = legacyStoragePrefix
                this.networkStatus = networkStatus
                this.maxBatchSize = maxBatchSize
            }
        return PostHogSendCachedEventsIntegration(config, PostHogApi(config), executor).also { integrations.add(it) }
    }

    @BeforeTest
    fun setUp() {
        PostHogSendCachedEventsIntegration.resetInstallationForTesting()
    }

    @AfterTest
    fun tearDown() {
        integrations.forEach { it.uninstall() }
        executor.shutdownAndAwaitTermination()
        PostHogSendCachedEventsIntegration.resetInstallationForTesting()
    }

    private fun legacyFile(prefix: String): QueueFile = QueueFile.Builder(File(prefix, "$PROJECT_TOKEN.tmp")).forceLegacy(true).build()

    private fun writeLegacyFile(content: List<String>): String =
        tmpDir.newFolder().absolutePath.also { prefix ->
            legacyFile(prefix).use { queue -> content.forEach { queue.add(it.toByteArray()) } }
        }

    private fun retained(prefix: String): List<String> = legacyFile(prefix).use { queue -> queue.map { it.toString(Charsets.UTF_8) } }

    @Test
    fun `install bails out if not connected`() {
        val prefix = writeLegacyFile(listOf(event))
        val http = servers.mockHttp()
        val sut =
            getSut(
                prefix,
                http.url("/").toString(),
                networkStatus =
                    object : PostHogNetworkStatus {
                        override fun isConnected() = false
                    },
            )
        sut.install(mock())
        executor.shutdownAndAwaitTermination()
        assertEquals(0, http.requestCount)
        assertEquals(listOf(event), retained(prefix))
    }

    @Test
    fun `removes file from the legacy queue if not a valid event`() {
        val prefix = writeLegacyFile(listOf("invalid event"))
        val http = servers.mockHttp()
        getSut(prefix, http.url("/").toString()).install(mock())
        executor.shutdownAndAwaitTermination()
        assertEquals(emptyList(), retained(prefix))
        assertEquals(0, http.requestCount)
    }

    @Test
    fun `sends event from the legacy queue`() {
        val prefix = writeLegacyFile(listOf(event))
        val http = servers.mockHttp()
        getSut(prefix, http.url("/").toString()).install(mock())
        executor.shutdownAndAwaitTermination()
        assertEquals(emptyList(), retained(prefix))
        assertEquals(1, http.requestCount)
        val request = assertNotNull(http.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/i/v1/analytics/events", request.path)
        val sent = sentEvents(request).single()
        assertEquals("testEvent", sent["event"])
        assertEquals("9740f814-0b10-42e2-b3d4-8f35af1b11f6", sent["distinct_id"])
        assertEquals(mapOf("testProperty" to "testValue"), sent["properties"])
    }

    @Suppress("UNCHECKED_CAST")
    private fun sentEvents(request: RecordedRequest): List<Map<String, Any?>> {
        val serializer = PostHogSerializer(PostHogConfig(PROJECT_TOKEN))
        val body = serializer.deserialize<Map<String, Any?>>(request.body.unGzip().reader())
        return body["batch"] as List<Map<String, Any?>>
    }

    @Test
    fun `sends a 2x cached event with its message_id as the uuid and the root set as a property`() {
        val legacyEvent =
            """
            {
              "message_id": "8837f4d8-01e3-4bb8-b3f3-16b8fa8173e5",
              "event": "testEvent",
              "distinct_id": "9740f814-0b10-42e2-b3d4-8f35af1b11f6",
              "properties": {"testProperty": "testValue"},
              "${'$'}set": {"name": "Max"},
              "timestamp": "2023-09-19T07:23:00.165Z"
            }
            """.trimIndent()
        val prefix = writeLegacyFile(listOf(legacyEvent))
        val http = servers.mockHttp()
        getSut(prefix, http.url("/").toString()).install(mock())
        executor.shutdownAndAwaitTermination()
        assertEquals(emptyList(), retained(prefix))
        val sent = sentEvents(assertNotNull(http.takeRequest(5, TimeUnit.SECONDS))).single()
        assertEquals("8837f4d8-01e3-4bb8-b3f3-16b8fa8173e5", sent["uuid"])
        assertEquals(mapOf("testProperty" to "testValue", "${'$'}set" to mapOf("name" to "Max")), sent["properties"])
    }

    @Test
    fun `re-queues the events capture asked to retry for the next launch`() {
        val retried = event.replace("8837f4d8-01e3-4bb8-b3f3-16b8fa8173e5", "11111111-0b10-42e2-b3d4-8f35af1b11f6")
        val prefix = writeLegacyFile(listOf(retried, event, event))
        val http =
            servers.mockHttp(
                response =
                    MockResponse().setBody(
                        "{\"results\":{\"11111111-0b10-42e2-b3d4-8f35af1b11f6\":{\"result\":\"retry\"}," +
                            "\"8837f4d8-01e3-4bb8-b3f3-16b8fa8173e5\":{\"result\":\"ok\"}}}",
                    ),
            )
        getSut(prefix, http.url("/").toString()).install(mock())
        executor.shutdownAndAwaitTermination()
        assertEquals(1, http.requestCount)
        assertEquals(listOf(retried), retained(prefix))
    }

    @Test
    fun `sends events from the legacy queue in batches`() {
        val prefix = writeLegacyFile(listOf(event, event))
        val http = servers.mockHttp(2)
        getSut(prefix, http.url("/").toString(), maxBatchSize = 1).install(mock())
        executor.shutdownAndAwaitTermination()
        assertEquals(emptyList(), retained(prefix))
        assertEquals(2, http.requestCount)
    }

    @Test
    fun `send a valid event and discard a broken event`() {
        val prefix = writeLegacyFile(listOf("invalid event", event))
        val http = servers.mockHttp(2)
        getSut(prefix, http.url("/").toString(), maxBatchSize = 1).install(mock())
        executor.shutdownAndAwaitTermination()
        assertEquals(emptyList(), retained(prefix))
        assertEquals(1, http.requestCount)
    }

    @Test
    fun `discards the events if returns 4xx`() {
        val prefix = writeLegacyFile(listOf(event, event))
        val http = servers.mockHttp(response = MockResponse().setResponseCode(400))
        getSut(prefix, http.url("/").toString()).install(mock())
        executor.shutdownAndAwaitTermination()
        assertEquals(emptyList(), retained(prefix))
        assertEquals(1, http.requestCount)
    }

    @Test
    fun `keeps the events if returns 503`() {
        val prefix = writeLegacyFile(listOf(event, event))
        val http = servers.mockHttp(response = MockResponse().setResponseCode(503))
        getSut(prefix, http.url("/").toString()).install(mock())
        executor.shutdownAndAwaitTermination()
        assertEquals(listOf(event, event), retained(prefix))
        assertEquals(1, http.requestCount)
    }

    @Test
    fun `discards the events if returns 3xx`() {
        val prefix = writeLegacyFile(listOf(event, event))
        val http = servers.mockHttp(response = MockResponse().setResponseCode(300))
        getSut(prefix, http.url("/").toString()).install(mock())
        executor.shutdownAndAwaitTermination()
        assertEquals(emptyList(), retained(prefix))
        assertEquals(1, http.requestCount)
    }

    @Test
    fun `keeps the events if no connection`() {
        val prefix = writeLegacyFile(listOf(event, event))
        val http = servers.mockHttp(response = MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        getSut(prefix, http.url("/").toString()).install(mock())
        executor.shutdownAndAwaitTermination()
        assertEquals(listOf(event, event), retained(prefix))
        assertEquals(1, http.requestCount)
    }
}

package com.posthog.internal

import com.posthog.API_KEY
import com.posthog.PostHogConfig
import com.posthog.TestHttpServers
import com.posthog.shutdownAndAwaitTermination
import com.posthog.unGzip
import okhttp3.mockwebserver.MockResponse
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
            PostHogConfig(API_KEY, host).apply {
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

    private fun legacyFile(prefix: String): QueueFile = QueueFile.Builder(File(prefix, "$API_KEY.tmp")).forceLegacy(true).build()

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
        val serializer = PostHogSerializer(PostHogConfig(API_KEY))
        val batch = serializer.deserialize<PostHogBatchEvent>(request.body.unGzip().reader())
        val sent = batch.batch.single()
        assertEquals("testEvent", sent.event)
        assertEquals("9740f814-0b10-42e2-b3d4-8f35af1b11f6", sent.distinctId)
        assertEquals(mapOf("testProperty" to "testValue"), sent.properties?.toMap())
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
    fun `keeps the events if returns 3xx`() {
        val prefix = writeLegacyFile(listOf(event, event))
        val http = servers.mockHttp(response = MockResponse().setResponseCode(300))
        getSut(prefix, http.url("/").toString()).install(mock())
        executor.shutdownAndAwaitTermination()
        assertEquals(listOf(event, event), retained(prefix))
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

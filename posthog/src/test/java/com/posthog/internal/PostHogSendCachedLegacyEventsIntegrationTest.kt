package com.posthog.internal

import com.posthog.PostHogConfig
import com.posthog.apiKey
import com.posthog.shutdownAndAwaitTermination
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Date
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class PostHogSendCachedLegacyEventsIntegrationTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    private val executor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("PostHogSendCachedEventsThread"))

    private val event = """
                {
                  "uuid": "8837f4d8-01e3-4bb8-b3f3-16b8fa8173e5",
                  "event": "testEvent",
                  "properties": {
                    "testProperty": "testValue",
                    "distinct_id": "9740f814-0b10-42e2-b3d4-8f35af1b11f6"
                  },
                  "timestamp": "2023-09-19T07:23:00.165Z"
                }
    """.trimIndent()

    private fun getSut(
        date: Date = Date(),
        legacyStoragePrefix: String = tmpDir.newFolder().absolutePath,
        host: String = "https://app.posthog.com",
        maxBatchSize: Int = 50,
        networkStatus: PostHogNetworkStatus? = null,
    ): PostHogSendCachedEventsIntegration {
        val config = PostHogConfig(apiKey, host = host).apply {
            this.legacyStoragePrefix = legacyStoragePrefix
            this.networkStatus = networkStatus
            this.maxBatchSize = maxBatchSize
        }
        val serializer = PostHogSerializer(config)
        val api = PostHogApi(config, serializer)
        return PostHogSendCachedEventsIntegration(config, api, serializer, date, executor = executor)
    }

    @AfterTest
    fun `set down`() {
        tmpDir.root.deleteRecursively()
    }

    private fun getLegacyFile(legacyStoragePrefix: String): QueueFile {
        val legacyDir = File(legacyStoragePrefix)
        val legacyFile = File(legacyDir, "$apiKey.tmp")
        return QueueFile.Builder(legacyFile)
            .forceLegacy(true)
            .build()
    }

    private fun writeLegacyFile(content: List<String> = listOf()): String {
        val legacyStoragePrefix = tmpDir.newFolder().absolutePath
        val legacy = getLegacyFile(legacyStoragePrefix)
        content.forEach {
            legacy.add(it.toByteArray())
        }
        legacy.close()
        return legacyStoragePrefix
    }

    private fun mockHttp(
        total: Int = 1,
        response: MockResponse = MockResponse()
            .setBody("""{"status":1}"""),
    ): MockWebServer {
        val mock = MockWebServer()
        mock.start()

        for (i in 1..total) {
            mock.enqueue(response)
        }
        return mock
    }

    @Test
    fun `install bails out if not connected`() {
        val legacyStoragePrefix = writeLegacyFile(listOf(event))

        val sut = getSut(Date(), legacyStoragePrefix = legacyStoragePrefix, networkStatus = {
            false
        })

        sut.install()

        executor.shutdownAndAwaitTermination()

        assertFalse(getLegacyFile(legacyStoragePrefix).isEmpty)
    }

    @Test
    fun `removes file from the legacy queue if not a valid event`() {
        val legacyStoragePrefix = writeLegacyFile(listOf("invalid event"))

        val sut = getSut(Date(), legacyStoragePrefix = legacyStoragePrefix)

        sut.install()

        executor.shutdownAndAwaitTermination()

        assertTrue(getLegacyFile(legacyStoragePrefix).isEmpty)
    }

    @Test
    fun `sends event from the legacy queue`() {
        val legacyStoragePrefix = writeLegacyFile(listOf(event))
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(Date(), legacyStoragePrefix = legacyStoragePrefix, host = url.toString())

        sut.install()

        executor.shutdownAndAwaitTermination()

        assertTrue(getLegacyFile(legacyStoragePrefix).isEmpty)
    }

    @Test
    fun `sends events from the legacy queue in batches`() {
        val legacyStoragePrefix = writeLegacyFile(listOf(event, event))
        val http = mockHttp(2)
        val url = http.url("/")

        val sut = getSut(Date(), legacyStoragePrefix = legacyStoragePrefix, host = url.toString(), maxBatchSize = 1)

        sut.install()

        executor.shutdownAndAwaitTermination()

        assertTrue(getLegacyFile(legacyStoragePrefix).isEmpty)
        assertEquals(2, http.requestCount)
    }

    @Test
    fun `send a valid event and discard a broken event`() {
        val legacyStoragePrefix = writeLegacyFile(listOf("invalid event", event))
        val http = mockHttp(2)
        val url = http.url("/")

        val sut = getSut(Date(), legacyStoragePrefix = legacyStoragePrefix, host = url.toString(), maxBatchSize = 1)

        sut.install()

        executor.shutdownAndAwaitTermination()

        assertTrue(getLegacyFile(legacyStoragePrefix).isEmpty)
        assertEquals(1, http.requestCount)
    }

    @Test
    fun `discards the events if returns 4xx`() {
        val legacyStoragePrefix = writeLegacyFile(listOf(event, event))
        val response = MockResponse().setResponseCode(400)
        val http = mockHttp(response = response)
        val url = http.url("/")

        val sut = getSut(Date(), legacyStoragePrefix = legacyStoragePrefix, host = url.toString())

        sut.install()

        executor.shutdownAndAwaitTermination()

        assertTrue(getLegacyFile(legacyStoragePrefix).isEmpty)
        assertEquals(1, http.requestCount)
    }

    @Test
    fun `keeps the events if returns 3xx`() {
        val legacyStoragePrefix = writeLegacyFile(listOf(event, event))
        val response = MockResponse().setResponseCode(300)
        val http = mockHttp(response = response)
        val url = http.url("/")

        val sut = getSut(Date(), legacyStoragePrefix = legacyStoragePrefix, host = url.toString())

        sut.install()

        executor.shutdownAndAwaitTermination()

        assertEquals(2, getLegacyFile(legacyStoragePrefix).size())
        assertEquals(1, http.requestCount)
    }

    @Test
    fun `keeps the events if no connection`() {
        val legacyStoragePrefix = writeLegacyFile(listOf(event, event))
        val response = MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
        val http = mockHttp(response = response)
        val url = http.url("/")

        val sut = getSut(Date(), legacyStoragePrefix = legacyStoragePrefix, host = url.toString())

        sut.install()

        executor.shutdownAndAwaitTermination()

        assertEquals(2, getLegacyFile(legacyStoragePrefix).size())
        assertEquals(1, http.requestCount)
    }
}

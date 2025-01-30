package com.posthog.internal

import com.posthog.API_KEY
import com.posthog.PostHogConfig
import com.posthog.mockHttp
import com.posthog.shutdownAndAwaitTermination
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
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

    private val executor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("Test"))

    private val file = File("src/test/resources/json/basic-event.json")
    private val event = file.readText()

    private fun getSut(
        date: Date = Date(),
        legacyStoragePrefix: String = tmpDir.newFolder().absolutePath,
        host: String,
        maxBatchSize: Int = 50,
        networkStatus: PostHogNetworkStatus? = null,
    ): PostHogSendCachedEventsIntegration {
        val config =
            PostHogConfig(API_KEY, host = host).apply {
                this.legacyStoragePrefix = legacyStoragePrefix
                this.networkStatus = networkStatus
                this.maxBatchSize = maxBatchSize
            }
        val api = PostHogApi(config)
        return PostHogSendCachedEventsIntegration(config, api, date, executor = executor)
    }

    @AfterTest
    fun `set down`() {
        tmpDir.root.deleteRecursively()
    }

    private fun getLegacyFile(legacyStoragePrefix: String): QueueFile {
        val legacyDir = File(legacyStoragePrefix)
        val legacyFile = File(legacyDir, "$API_KEY.tmp")
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

    @Test
    fun `install bails out if not connected`() {
        val legacyStoragePrefix = writeLegacyFile(listOf(event))

        val sut =
            getSut(Date(), legacyStoragePrefix = legacyStoragePrefix, host = "host", networkStatus = {
                false
            })

        sut.install(mock())

        executor.shutdownAndAwaitTermination()

        assertFalse(getLegacyFile(legacyStoragePrefix).isEmpty)

        sut.uninstall()
    }

    @Test
    fun `removes file from the legacy queue if not a valid event`() {
        val legacyStoragePrefix = writeLegacyFile(listOf("invalid event"))

        val sut = getSut(Date(), legacyStoragePrefix = legacyStoragePrefix, host = "host")

        sut.install(mock())

        executor.shutdownAndAwaitTermination()

        assertTrue(getLegacyFile(legacyStoragePrefix).isEmpty)

        sut.uninstall()
    }

    @Test
    fun `sends event from the legacy queue`() {
        val legacyStoragePrefix = writeLegacyFile(listOf(event))
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(Date(), legacyStoragePrefix = legacyStoragePrefix, host = url.toString())

        sut.install(mock())

        executor.shutdownAndAwaitTermination()

        assertTrue(getLegacyFile(legacyStoragePrefix).isEmpty)

        sut.uninstall()
    }

    @Test
    fun `sends events from the legacy queue in batches`() {
        val legacyStoragePrefix = writeLegacyFile(listOf(event, event))
        val http = mockHttp(2)
        val url = http.url("/")

        val sut = getSut(Date(), legacyStoragePrefix = legacyStoragePrefix, host = url.toString(), maxBatchSize = 1)

        sut.install(mock())

        executor.shutdownAndAwaitTermination()

        assertTrue(getLegacyFile(legacyStoragePrefix).isEmpty)
        assertEquals(2, http.requestCount)

        sut.uninstall()
    }

    @Test
    fun `send a valid event and discard a broken event`() {
        val legacyStoragePrefix = writeLegacyFile(listOf("invalid event", event))
        val http = mockHttp(2)
        val url = http.url("/")

        val sut = getSut(Date(), legacyStoragePrefix = legacyStoragePrefix, host = url.toString(), maxBatchSize = 1)

        sut.install(mock())

        executor.shutdownAndAwaitTermination()

        assertTrue(getLegacyFile(legacyStoragePrefix).isEmpty)
        assertEquals(1, http.requestCount)

        sut.uninstall()
    }

    @Test
    fun `discards the events if returns 4xx`() {
        val legacyStoragePrefix = writeLegacyFile(listOf(event, event))
        val response = MockResponse().setResponseCode(400)
        val http = mockHttp(response = response)
        val url = http.url("/")

        val sut = getSut(Date(), legacyStoragePrefix = legacyStoragePrefix, host = url.toString())

        sut.install(mock())

        executor.shutdownAndAwaitTermination()

        assertTrue(getLegacyFile(legacyStoragePrefix).isEmpty)
        assertEquals(1, http.requestCount)

        sut.uninstall()
    }

    @Test
    fun `keeps the events if returns 3xx`() {
        val legacyStoragePrefix = writeLegacyFile(listOf(event, event))
        val response = MockResponse().setResponseCode(300)
        val http = mockHttp(response = response)
        val url = http.url("/")

        val sut = getSut(Date(), legacyStoragePrefix = legacyStoragePrefix, host = url.toString())

        sut.install(mock())

        executor.shutdownAndAwaitTermination()

        assertEquals(2, getLegacyFile(legacyStoragePrefix).size())
        assertEquals(1, http.requestCount)

        sut.uninstall()
    }

    @Test
    fun `keeps the events if no connection`() {
        val legacyStoragePrefix = writeLegacyFile(listOf(event, event))
        val response = MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
        val http = mockHttp(response = response)
        val url = http.url("/")

        val sut = getSut(Date(), legacyStoragePrefix = legacyStoragePrefix, host = url.toString())

        sut.install(mock())

        executor.shutdownAndAwaitTermination()

        assertEquals(2, getLegacyFile(legacyStoragePrefix).size())
        assertEquals(1, http.requestCount)

        sut.uninstall()
    }
}

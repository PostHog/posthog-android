package com.posthog.internal

import com.google.gson.internal.bind.util.ISO8601Utils
import com.posthog.PostHogConfig
import com.posthog.apiKey
import com.posthog.mockHttp
import com.posthog.shutdownAndAwaitTermination
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.text.ParsePosition
import java.util.Calendar
import java.util.Date
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class PostHogSendCachedEventsIntegrationTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    private val executor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("Test"))

    private val file = File("src/test/resources/json/basic-event.json")
    private val event = file.readText()

    private fun getSut(
        date: Date = Date(),
        storagePrefix: String = tmpDir.newFolder().absolutePath,
        host: String,
        maxBatchSize: Int = 50,
        networkStatus: PostHogNetworkStatus? = null,
    ): PostHogSendCachedEventsIntegration {
        val config = PostHogConfig(apiKey, host = host).apply {
            this.storagePrefix = storagePrefix
            this.networkStatus = networkStatus
            this.maxBatchSize = maxBatchSize
        }
        val serializer = PostHogSerializer(config)
        val dateProvider = PostHogCalendarDateProvider()
        val api = PostHogApi(config, serializer, dateProvider)
        return PostHogSendCachedEventsIntegration(config, api, serializer, date, executor = executor)
    }

    @AfterTest
    fun `set down`() {
        tmpDir.root.deleteRecursively()
    }

    private fun writeFile(content: List<String> = emptyList(), date: Date? = null): String {
        val storagePrefix = tmpDir.newFolder().absolutePath
        val fullFile = File(storagePrefix, apiKey)
        fullFile.mkdirs()

        content.forEach {
            val file = File(fullFile.absoluteFile, "${UUID.randomUUID()}.event")
            file.writeText(it)
            date?.let { theDate ->
                val cal = Calendar.getInstance()
                cal.time = theDate
                // -1 sec from date
                cal.add(Calendar.SECOND, -1)
                file.setLastModified(cal.time.time)
            }
        }

        return storagePrefix
    }

    @Test
    fun `install bails out if not connected`() {
        val storagePrefix = writeFile(listOf(event))

        val sut = getSut(storagePrefix = storagePrefix, host = "host", networkStatus = {
            false
        })

        sut.install()

        executor.shutdownAndAwaitTermination()

        assertFalse(File(storagePrefix, apiKey).listFiles()!!.isEmpty())
    }

    @Test
    fun `removes file from the queue if not a valid event`() {
        val storagePrefix = writeFile(listOf("invalid event"))

        val sut = getSut(storagePrefix = storagePrefix, host = "host")

        sut.install()

        executor.shutdownAndAwaitTermination()

        assertTrue(File(storagePrefix, apiKey).listFiles()!!.isEmpty())
    }

    @Test
    fun `sends event from the queue`() {
        val storagePrefix = writeFile(listOf(event))
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(storagePrefix = storagePrefix, host = url.toString())

        sut.install()

        executor.shutdownAndAwaitTermination()

        assertTrue(File(storagePrefix, apiKey).listFiles()!!.isEmpty())
    }

    @Test
    fun `sends events from the queue in batches`() {
        val storagePrefix = writeFile(listOf(event, event))
        val http = mockHttp(2)
        val url = http.url("/")

        val sut = getSut(storagePrefix = storagePrefix, host = url.toString(), maxBatchSize = 1)

        sut.install()

        executor.shutdownAndAwaitTermination()

        assertTrue(File(storagePrefix, apiKey).listFiles()!!.isEmpty())
        assertEquals(2, http.requestCount)
    }

    @Test
    fun `send a valid event and discard a broken event`() {
        val storagePrefix = writeFile(listOf("invalid event", event))
        val http = mockHttp(2)
        val url = http.url("/")

        val sut = getSut(storagePrefix = storagePrefix, host = url.toString(), maxBatchSize = 1)

        sut.install()

        executor.shutdownAndAwaitTermination()

        assertTrue(File(storagePrefix, apiKey).listFiles()!!.isEmpty())
        assertEquals(1, http.requestCount)
    }

    @Test
    fun `discards the events if returns 4xx`() {
        val storagePrefix = writeFile(listOf(event, event))
        val response = MockResponse().setResponseCode(400)
        val http = mockHttp(response = response)
        val url = http.url("/")

        val sut = getSut(storagePrefix = storagePrefix, host = url.toString())

        sut.install()

        executor.shutdownAndAwaitTermination()

        assertTrue(File(storagePrefix, apiKey).listFiles()!!.isEmpty())
        assertEquals(1, http.requestCount)
    }

    @Test
    fun `keeps the events if returns 3xx`() {
        val storagePrefix = writeFile(listOf(event, event))
        val response = MockResponse().setResponseCode(300)
        val http = mockHttp(response = response)
        val url = http.url("/")

        val sut = getSut(storagePrefix = storagePrefix, host = url.toString())

        sut.install()

        executor.shutdownAndAwaitTermination()

        assertEquals(2, File(storagePrefix, apiKey).listFiles()!!.size)
        assertEquals(1, http.requestCount)
    }

    @Test
    fun `keeps the events if no connection`() {
        val storagePrefix = writeFile(listOf(event, event))
        val response = MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
        val http = mockHttp(response = response)
        val url = http.url("/")

        val sut = getSut(storagePrefix = storagePrefix, host = url.toString())

        sut.install()

        executor.shutdownAndAwaitTermination()

        assertEquals(2, File(storagePrefix, apiKey).listFiles()!!.size)
        assertEquals(1, http.requestCount)
    }

    @Test
    fun `only send files past the integration run`() {
        val date = ISO8601Utils.parse("2023-09-20T11:58:49.000Z", ParsePosition(0))
        val storagePrefix = writeFile(listOf(event), date = date)
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(date, storagePrefix = storagePrefix, host = url.toString(), maxBatchSize = 1)

        // write a new file
        val folder = File(storagePrefix, apiKey)
        val file = File(folder, "${UUID.randomUUID()}.event")

        val tempEvent = File("src/test/resources/json/other-event.json").readText()
        file.writeText(tempEvent)

        val cal = Calendar.getInstance()
        cal.time = date
        cal.add(Calendar.SECOND, 1)
        // +1 sec
        file.setLastModified(cal.time.time)

        sut.install()

        executor.shutdownAndAwaitTermination()

        assertEquals(1, http.requestCount)
        val files = File(storagePrefix, apiKey).listFiles()!!
        assertEquals(1, files.size)
        assertEquals(tempEvent, files[0].readText())
    }
}

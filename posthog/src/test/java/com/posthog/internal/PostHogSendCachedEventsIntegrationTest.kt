package com.posthog.internal

import com.google.gson.internal.bind.util.ISO8601Utils
import com.posthog.API_KEY
import com.posthog.PostHogConfig
import com.posthog.mockHttp
import com.posthog.shutdownAndAwaitTermination
import com.posthog.vendor.uuid.TimeBasedEpochGenerator
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import java.io.File
import java.text.ParsePosition
import java.util.Calendar
import java.util.Date
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
        val config =
            PostHogConfig(API_KEY, host = host).apply {
                this.storagePrefix = storagePrefix
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

    private fun writeFile(
        content: List<String> = emptyList(),
        date: Date? = null,
    ): String {
        val storagePrefix = tmpDir.newFolder().absolutePath
        val fullFile = File(storagePrefix, API_KEY)
        fullFile.mkdirs()

        content.forEach {
            val uuid = TimeBasedEpochGenerator.generate()
            val file = File(fullFile.absoluteFile, "$uuid.event")
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

        val sut =
            getSut(storagePrefix = storagePrefix, host = "host", networkStatus = {
                false
            })

        sut.install(mock())

        executor.shutdownAndAwaitTermination()

        assertFalse(File(storagePrefix, API_KEY).listFiles()!!.isEmpty())

        sut.uninstall()
    }

    @Test
    fun `removes file from the queue if not a valid event`() {
        val storagePrefix = writeFile(listOf("invalid event"))

        val sut = getSut(storagePrefix = storagePrefix, host = "host")

        sut.install(mock())

        executor.shutdownAndAwaitTermination()

        assertTrue(File(storagePrefix, API_KEY).listFiles()!!.isEmpty())

        sut.uninstall()
    }

    @Test
    fun `sends event from the queue`() {
        val storagePrefix = writeFile(listOf(event))
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(storagePrefix = storagePrefix, host = url.toString())

        sut.install(mock())

        executor.shutdownAndAwaitTermination()

        assertTrue(File(storagePrefix, API_KEY).listFiles()!!.isEmpty())

        sut.uninstall()
    }

    @Test
    fun `sends events from the queue in batches`() {
        val storagePrefix = writeFile(listOf(event, event))
        val http = mockHttp(2)
        val url = http.url("/")

        val sut = getSut(storagePrefix = storagePrefix, host = url.toString(), maxBatchSize = 1)

        sut.install(mock())

        executor.shutdownAndAwaitTermination()

        assertTrue(File(storagePrefix, API_KEY).listFiles()!!.isEmpty())
        assertEquals(2, http.requestCount)

        sut.uninstall()
    }

    @Test
    fun `send a valid event and discard a broken event`() {
        val storagePrefix = writeFile(listOf("invalid event", event))
        val http = mockHttp(2)
        val url = http.url("/")

        val sut = getSut(storagePrefix = storagePrefix, host = url.toString(), maxBatchSize = 1)

        sut.install(mock())

        executor.shutdownAndAwaitTermination()

        assertTrue(File(storagePrefix, API_KEY).listFiles()!!.isEmpty())
        assertEquals(1, http.requestCount)

        sut.uninstall()
    }

    @Test
    fun `discards the events if returns 4xx`() {
        val storagePrefix = writeFile(listOf(event, event))
        val response = MockResponse().setResponseCode(400)
        val http = mockHttp(response = response)
        val url = http.url("/")

        val sut = getSut(storagePrefix = storagePrefix, host = url.toString())

        sut.install(mock())

        executor.shutdownAndAwaitTermination()

        assertTrue(File(storagePrefix, API_KEY).listFiles()!!.isEmpty())
        assertEquals(1, http.requestCount)

        sut.uninstall()
    }

    @Test
    fun `keeps the events if returns 3xx`() {
        val storagePrefix = writeFile(listOf(event, event))
        val response = MockResponse().setResponseCode(300)
        val http = mockHttp(response = response)
        val url = http.url("/")

        val sut = getSut(storagePrefix = storagePrefix, host = url.toString())

        sut.install(mock())

        executor.shutdownAndAwaitTermination()

        assertEquals(2, File(storagePrefix, API_KEY).listFiles()!!.size)
        assertEquals(1, http.requestCount)

        sut.uninstall()
    }

    @Test
    fun `keeps the events if no connection`() {
        val storagePrefix = writeFile(listOf(event, event))
        val response = MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
        val http = mockHttp(response = response)
        val url = http.url("/")

        val sut = getSut(storagePrefix = storagePrefix, host = url.toString())

        sut.install(mock())

        executor.shutdownAndAwaitTermination()

        assertEquals(2, File(storagePrefix, API_KEY).listFiles()!!.size)
        assertEquals(1, http.requestCount)

        sut.uninstall()
    }

    @Test
    fun `only send files past the integration run`() {
        val date = ISO8601Utils.parse("2023-09-20T11:58:49.000Z", ParsePosition(0))
        val storagePrefix = writeFile(listOf(event), date = date)
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(date, storagePrefix = storagePrefix, host = url.toString(), maxBatchSize = 1)

        // write a new file
        val folder = File(storagePrefix, API_KEY)
        val uuid = TimeBasedEpochGenerator.generate()
        val file = File(folder, "$uuid.event")

        val tempEvent = File("src/test/resources/json/other-event.json").readText()
        file.writeText(tempEvent)

        val cal = Calendar.getInstance()
        cal.time = date
        cal.add(Calendar.SECOND, 1)
        // +1 sec
        file.setLastModified(cal.time.time)

        sut.install(mock())

        executor.shutdownAndAwaitTermination()

        assertEquals(1, http.requestCount)
        val files = File(storagePrefix, API_KEY).listFiles()!!
        assertEquals(1, files.size)
        assertEquals(tempEvent, files[0].readText())

        sut.uninstall()
    }
}

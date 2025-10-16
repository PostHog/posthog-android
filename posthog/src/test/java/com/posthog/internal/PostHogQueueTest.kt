package com.posthog.internal

import com.google.gson.internal.bind.util.ISO8601Utils
import com.posthog.API_KEY
import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import com.posthog.PostHogEventName
import com.posthog.awaitExecution
import com.posthog.generateEvent
import com.posthog.internal.errortracking.ThrowableCoercer
import com.posthog.mockHttp
import com.posthog.shutdownAndAwaitTermination
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.text.ParsePosition
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class PostHogQueueTest {
    private val executor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("Test"))

    @get:Rule
    val tmpDir = TemporaryFolder()

    private fun getSut(
        host: String,
        maxQueueSize: Int = 1000,
        storagePrefix: String = tmpDir.newFolder().absolutePath,
        flushAt: Int = 20,
        dateProvider: PostHogDateProvider = PostHogDeviceDateProvider(),
        maxBatchSize: Int = 50,
        networkStatus: PostHogNetworkStatus? = null,
    ): PostHogQueue {
        val config =
            PostHogConfig(API_KEY, host).apply {
                this.maxQueueSize = maxQueueSize
                this.storagePrefix = storagePrefix
                this.flushAt = flushAt
                this.networkStatus = networkStatus
                this.maxBatchSize = maxBatchSize
                this.dateProvider = dateProvider
            }
        val api = PostHogApi(config)
        return PostHogQueue(config, api, PostHogApiEndpoint.BATCH, config.storagePrefix, executor = executor)
    }

    @Test
    fun `respect maxQueueSize and deletes the first if full`() {
        val http = mockHttp()
        val url = http.url("/")

        val event1 = generateEvent("1")
        val event2 = generateEvent("2")
        val event3 = generateEvent("3")

        val sut = getSut(host = url.toString(), maxQueueSize = 2)

        sut.add(event1)
        sut.add(event2)
        sut.add(event3)

        executor.shutdownAndAwaitTermination()

        assertEquals(2, sut.dequeList.size)
    }

    @Test
    fun `creates folder if it does not exist`() {
        val http = mockHttp()
        val url = http.url("/")

        val path = tmpDir.newFolder().absolutePath
        val sut = getSut(host = url.toString(), storagePrefix = path)

        sut.add(generateEvent())

        executor.shutdownAndAwaitTermination()

        assertTrue(File(path, API_KEY).exists())
    }

    @Test
    fun `serializes event to disk`() {
        val http = mockHttp()
        val url = http.url("/")

        val path = tmpDir.newFolder().absolutePath
        val sut = getSut(host = url.toString(), storagePrefix = path)

        sut.add(generateEvent())

        executor.shutdownAndAwaitTermination()

        assertEquals(1, File(path, API_KEY).listFiles()!!.size)
    }

    @Test
    fun `does not flush if not above threshold`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        sut.add(generateEvent())

        executor.shutdownAndAwaitTermination()

        assertEquals(0, http.requestCount)
    }

    @Test
    fun `flushes if above threshold`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(host = url.toString(), flushAt = 1)

        sut.add(generateEvent())

        executor.shutdownAndAwaitTermination()

        assertEquals(1, http.requestCount)
    }

    @Test
    fun `does not flush if paused`() {
        val http = mockHttp(response = MockResponse().setResponseCode(400).setBody("error"))
        val url = http.url("/")

        val fakeCurrentTime = FakePostHogDateProvider()
        val sut = getSut(host = url.toString(), flushAt = 1, dateProvider = fakeCurrentTime)
        // if this code lives up to 2050 we are fine.
        val date = ISO8601Utils.parse("2050-09-20T11:58:49.000Z", ParsePosition(0))
        fakeCurrentTime.setAddSecondsToCurrentDate(date)

        sut.add(generateEvent())

        executor.awaitExecution()

        http.enqueue(
            MockResponse()
                .setBody(""),
        )
        sut.add(generateEvent())

        executor.shutdownAndAwaitTermination()

        // only 1 since the second won't be triggered
        assertEquals(1, http.requestCount)
    }

    @Test
    fun `does not flush if not connected`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut =
            getSut(host = url.toString(), flushAt = 1, networkStatus = {
                false
            })

        sut.add(generateEvent())

        executor.shutdownAndAwaitTermination()

        assertEquals(0, http.requestCount)
    }

    @Test
    fun `does not flush if not connected but try to flush again`() {
        val http = mockHttp()
        val url = http.url("/")

        var connected = false
        val sut =
            getSut(host = url.toString(), flushAt = 1, networkStatus = {
                connected
            })

        sut.add(generateEvent())

        executor.awaitExecution()

        connected = true

        sut.add(generateEvent())

        executor.shutdownAndAwaitTermination()

        assertEquals(1, http.requestCount)
    }

    @Test
    fun `does not delete file if API is 3xx`() {
        val http = mockHttp(response = MockResponse().setResponseCode(300).setBody("error"))
        val url = http.url("/")

        val path = tmpDir.newFolder().absolutePath
        val sut = getSut(host = url.toString(), flushAt = 1, storagePrefix = path)

        sut.add(generateEvent())

        executor.shutdownAndAwaitTermination()

        assertEquals(1, sut.dequeList.size)
        assertEquals(1, File(path, API_KEY).listFiles()!!.size)
    }

    @Test
    fun `does not delete file if network error`() {
        val http = mockHttp(response = MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val url = http.url("/")

        val path = tmpDir.newFolder().absolutePath
        val sut = getSut(host = url.toString(), flushAt = 1, storagePrefix = path)

        sut.add(generateEvent())

        executor.shutdownAndAwaitTermination()

        assertEquals(1, sut.dequeList.size)
        assertEquals(1, File(path, API_KEY).listFiles()!!.size)
    }

    @Test
    fun `deletes the files if successful`() {
        val http = mockHttp()
        val url = http.url("/")

        val path = tmpDir.newFolder().absolutePath
        val sut = getSut(host = url.toString(), flushAt = 1, storagePrefix = path)

        sut.add(generateEvent())

        executor.shutdownAndAwaitTermination()

        assertEquals(1, http.requestCount)
        assertEquals(0, sut.dequeList.size)
        assertEquals(0, File(path, API_KEY).listFiles()!!.size)
    }

    @Test
    fun `clear deletes all files and clean the queue`() {
        val http = mockHttp(response = MockResponse().setResponseCode(300).setBody("error"))
        val url = http.url("/")

        val path = tmpDir.newFolder().absolutePath
        val sut = getSut(host = url.toString(), flushAt = 1, storagePrefix = path)

        sut.add(generateEvent())

        executor.awaitExecution()

        assertEquals(1, sut.dequeList.size)
        assertEquals(1, File(path, API_KEY).listFiles()!!.size)

        sut.clear()

        executor.shutdownAndAwaitTermination()

        assertEquals(0, sut.dequeList.size)
        assertEquals(0, File(path, API_KEY).listFiles()!!.size)
    }

    @Test
    fun `flush flushes the queue`() {
        val http = mockHttp(response = MockResponse().setResponseCode(300).setBody("error"))
        val url = http.url("/")

        val fakeCurrentTime = FakePostHogDateProvider()
        val path = tmpDir.newFolder().absolutePath
        val sut = getSut(host = url.toString(), flushAt = 1, storagePrefix = path, dateProvider = fakeCurrentTime)

        sut.add(generateEvent())

        executor.awaitExecution()

        assertEquals(1, sut.dequeList.size)
        assertEquals(1, File(path, API_KEY).listFiles()!!.size)

        http.enqueue(MockResponse().setBody(""))

        sut.flush()

        executor.shutdownAndAwaitTermination()

        assertEquals(0, sut.dequeList.size)
        assertEquals(0, File(path, API_KEY).listFiles()!!.size)
    }

    @Test
    fun `flush flushes the queue but respect maxBatchSize`() {
        val http = mockHttp(response = MockResponse().setResponseCode(300).setBody("error"))
        val url = http.url("/")

        val fakeCurrentTime = FakePostHogDateProvider()
        val path = tmpDir.newFolder().absolutePath
        val sut = getSut(host = url.toString(), flushAt = 1, storagePrefix = path, dateProvider = fakeCurrentTime, maxBatchSize = 1)

        // to be sure that the delay is before now
        val date = ISO8601Utils.parse("1970-09-20T11:58:49.000Z", ParsePosition(0))
        fakeCurrentTime.setAddSecondsToCurrentDate(date)

        sut.add(generateEvent())

        executor.awaitExecution()

        assertEquals(1, sut.dequeList.size)
        assertEquals(1, File(path, API_KEY).listFiles()!!.size)

        http.enqueue(MockResponse().setResponseCode(300).setBody("error"))

        sut.add(generateEvent(givenUuuid = UUID.randomUUID()))

        executor.awaitExecution()

        assertEquals(2, sut.dequeList.size)
        assertEquals(2, File(path, API_KEY).listFiles()!!.size)

        http.enqueue(MockResponse().setBody(""))
        http.enqueue(MockResponse().setBody(""))

        sut.flush()

        executor.shutdownAndAwaitTermination()

        assertEquals(0, sut.dequeList.size)
        assertEquals(0, File(path, API_KEY).listFiles()!!.size)
        assertEquals(4, http.requestCount)
    }

    @Test
    fun `reduces batch size if 413`() {
        val e = PostHogApiError(413, "", null)
        val config = PostHogConfig(API_KEY)

        assertFalse(deleteFilesIfAPIError(e, config))
        assertEquals(config.maxBatchSize, 25) // default 50
        assertEquals(config.flushAt, 10) // default 20
    }

    @Test
    fun `delete files if batch is min already`() {
        val e = PostHogApiError(413, "", null)
        val config =
            PostHogConfig(API_KEY).apply {
                maxBatchSize = 1
                flushAt = 1
            }

        assertTrue(deleteFilesIfAPIError(e, config))
        assertEquals(config.maxBatchSize, 1)
        assertEquals(config.flushAt, 1)
    }

    @Test
    fun `delete files if errored`() {
        val e = PostHogApiError(400, "", null)
        val config = PostHogConfig(API_KEY)

        assertTrue(deleteFilesIfAPIError(e, config))
    }

    @Test
    fun `flush the event right away if exception and fatal`() {
        val http = mockHttp()
        val url = http.url("/")

        val fakeCurrentTime = FakePostHogDateProvider()
        val path = tmpDir.newFolder().absolutePath
        val sut = getSut(host = url.toString(), flushAt = 1, storagePrefix = path, dateProvider = fakeCurrentTime)

        val props = mutableMapOf<String, Any>(ThrowableCoercer.EXCEPTION_LEVEL_ATTRIBUTE to ThrowableCoercer.EXCEPTION_LEVEL_FATAL)
        val event = PostHogEvent(PostHogEventName.EXCEPTION.event, "123", properties = props)

        sut.add(event)

        // we dont call shutdownAndAwaitTermination here

        assertEquals(0, sut.dequeList.size)
        assertEquals(0, File(path, API_KEY).listFiles()!!.size)
    }
}

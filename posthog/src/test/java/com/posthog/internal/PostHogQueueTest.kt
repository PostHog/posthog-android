package com.posthog.internal

import com.posthog.API_KEY
import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import com.posthog.PostHogEventName
import com.posthog.awaitExecution
import com.posthog.generateEvent
import com.posthog.internal.errortracking.ThrowableCoercer
import com.posthog.mockHttp
import com.posthog.shutdownAndAwaitTermination
import com.posthog.vendor.uuid.TimeBasedEpochGenerator
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
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
        val date = parseISO8601Date("2050-09-20T11:58:49.000Z")!!
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
            getSut(
                host = url.toString(),
                flushAt = 1,
                networkStatus =
                    object : PostHogNetworkStatus {
                        override fun isConnected() = false
                    },
            )

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
            getSut(
                host = url.toString(),
                flushAt = 1,
                networkStatus =
                    object : PostHogNetworkStatus {
                        override fun isConnected() = connected
                    },
            )

        sut.add(generateEvent())

        executor.awaitExecution()

        connected = true

        sut.add(generateEvent())

        executor.shutdownAndAwaitTermination()

        assertEquals(1, http.requestCount)
    }

    @Test
    fun `flushes queued events when network becomes available`() {
        val http = mockHttp()
        val url = http.url("/")

        var connected = false
        var onAvailableCallback: (() -> Unit)? = null
        val sut =
            getSut(
                host = url.toString(),
                flushAt = 1,
                networkStatus =
                    object : PostHogNetworkStatus {
                        override fun isConnected() = connected

                        override fun register(callback: () -> Unit) {
                            onAvailableCallback = callback
                        }
                    },
            )

        sut.start()

        sut.add(generateEvent())

        executor.awaitExecution()

        // event was queued but not flushed because network is disconnected
        assertEquals(0, http.requestCount)
        assertEquals(1, sut.dequeList.size)

        // simulate network becoming available
        connected = true
        onAvailableCallback?.invoke()

        executor.shutdownAndAwaitTermination()

        assertEquals(1, http.requestCount)
        assertEquals(0, sut.dequeList.size)
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
        // set pause to the past so flush() is not blocked by backoff
        val date = parseISO8601Date("1970-09-20T11:58:49.000Z")!!
        fakeCurrentTime.setAddSecondsToCurrentDate(date)

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
        val date = parseISO8601Date("1970-09-20T11:58:49.000Z")!!
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
    fun `retries on 500`() {
        val e = PostHogApiError(500, "", null)
        val config = PostHogConfig(API_KEY)

        assertFalse(deleteFilesIfAPIError(e, config))
    }

    @Test
    fun `retries on 502`() {
        val e = PostHogApiError(502, "", null)
        val config = PostHogConfig(API_KEY)

        assertFalse(deleteFilesIfAPIError(e, config))
    }

    @Test
    fun `retries on 429`() {
        val e = PostHogApiError(429, "", null)
        val config = PostHogConfig(API_KEY)

        assertFalse(deleteFilesIfAPIError(e, config))
    }

    @Test
    fun `retries on 504`() {
        val e = PostHogApiError(504, "", null)
        val config = PostHogConfig(API_KEY)

        assertFalse(deleteFilesIfAPIError(e, config))
    }

    @Test
    fun `retries on 503`() {
        val e = PostHogApiError(503, "", null)
        val config = PostHogConfig(API_KEY)

        assertFalse(deleteFilesIfAPIError(e, config))
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

    @Test
    fun `loads cached events from disk on first add`() {
        val http = mockHttp()
        val url = http.url("/")

        val path = tmpDir.newFolder().absolutePath
        val dir = File(path, API_KEY)
        dir.mkdirs()

        val eventFile = File("src/test/resources/json/basic-event.json")
        val eventContent = eventFile.readText()

        // write 3 cached event files
        for (i in 1..3) {
            val uuid = TimeBasedEpochGenerator.generate()
            val file = File(dir, "$uuid.event")
            file.writeText(eventContent)
            file.setLastModified(System.currentTimeMillis() - (4 - i) * 1000L)
        }

        val sut = getSut(host = url.toString(), storagePrefix = path)

        // trigger lazy loading via add
        sut.add(generateEvent())

        executor.shutdownAndAwaitTermination()

        // 3 cached + 1 new
        assertEquals(4, sut.dequeList.size)
    }

    @Test
    fun `loads cached events and flushes them when add triggers threshold`() {
        val http = mockHttp()
        val url = http.url("/")

        val path = tmpDir.newFolder().absolutePath
        val dir = File(path, API_KEY)
        dir.mkdirs()

        val eventFile = File("src/test/resources/json/basic-event.json")
        val eventContent = eventFile.readText()

        val uuid = TimeBasedEpochGenerator.generate()
        val file = File(dir, "$uuid.event")
        file.writeText(eventContent)

        // flushAt=1 so the cached event triggers a flush on the first add
        val sut = getSut(host = url.toString(), storagePrefix = path, flushAt = 1)

        // add triggers ensureCachedEventsLoaded (1 cached) + new event, hitting flushAt
        sut.add(generateEvent())

        executor.shutdownAndAwaitTermination()

        assertEquals(1, http.requestCount)
        assertEquals(0, sut.dequeList.size)
        assertEquals(0, File(path, API_KEY).listFiles()!!.size)
    }

    @Test
    fun `no cached events loaded if directory does not exist`() {
        val http = mockHttp()
        val url = http.url("/")

        val path = tmpDir.newFolder().absolutePath
        // don't create the API_KEY subdirectory

        val sut = getSut(host = url.toString(), storagePrefix = path)

        // trigger lazy loading, should not fail
        sut.add(generateEvent())

        executor.shutdownAndAwaitTermination()

        // only the new event
        assertEquals(1, sut.dequeList.size)
    }

    @Test
    fun `cached events are loaded in sorted order by last modified`() {
        val http = mockHttp()
        val url = http.url("/")

        val path = tmpDir.newFolder().absolutePath
        val dir = File(path, API_KEY)
        dir.mkdirs()

        val eventFile = File("src/test/resources/json/basic-event.json")
        val eventContent = eventFile.readText()

        // write cached event files with different timestamps
        val uuid1 = TimeBasedEpochGenerator.generate()
        val file1 = File(dir, "$uuid1.event")
        file1.writeText(eventContent)
        file1.setLastModified(System.currentTimeMillis() - 20000L)

        val uuid2 = TimeBasedEpochGenerator.generate()
        val file2 = File(dir, "$uuid2.event")
        file2.writeText(eventContent)
        file2.setLastModified(System.currentTimeMillis() - 10000L)

        val sut = getSut(host = url.toString(), storagePrefix = path)

        // trigger lazy loading via add
        sut.add(generateEvent())

        executor.shutdownAndAwaitTermination()

        val dequeFiles = sut.dequeList
        // cached files first (sorted by last modified), then the new event
        assertEquals(3, dequeFiles.size)
        assertEquals(file1.name, dequeFiles[0].name)
        assertEquals(file2.name, dequeFiles[1].name)
    }
}

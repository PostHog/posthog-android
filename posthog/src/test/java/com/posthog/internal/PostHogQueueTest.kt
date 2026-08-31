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
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.Date
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
        maxRetries: Int = 3,
        httpClient: OkHttpClient? = null,
    ): PostHogQueue<PostHogEvent> {
        val config =
            PostHogConfig(API_KEY, host).apply {
                this.maxQueueSize = maxQueueSize
                this.storagePrefix = storagePrefix
                this.flushAt = flushAt
                this.networkStatus = networkStatus
                this.maxBatchSize = maxBatchSize
                this.dateProvider = dateProvider
                this.maxRetries = maxRetries
                this.httpClient = httpClient
            }
        val api = PostHogApi(config)
        return PostHogQueue(config, EndpointSpec.batch(config, api, config.storagePrefix), executor)
    }

    private fun waitUntil(
        timeoutMillis: Long = 5_000,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
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
        val http = mockHttp(response = MockResponse().setResponseCode(503).setBody("error"))
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
    fun `known offline flushes do not consume retries and availability drains the queue`() {
        val http = mockHttp()
        var connected = false
        var onAvailableCallback: (() -> Unit)? = null
        val path = tmpDir.newFolder().absolutePath
        val sut =
            getSut(
                host = http.url("/").toString(),
                storagePrefix = path,
                flushAt = 1,
                maxRetries = 0,
                networkStatus =
                    object : PostHogNetworkStatus {
                        override fun isConnected() = connected

                        override fun register(callback: () -> Unit) {
                            onAvailableCallback = callback
                        }
                    },
            )

        try {
            sut.start()
            sut.add(generateEvent())
            executor.awaitExecution()

            repeat(3) {
                sut.flush()
                executor.awaitExecution()
            }

            assertEquals(0, http.requestCount)
            assertEquals(0, sut.currentRetryCountForTesting)
            assertEquals(1, sut.dequeList.size)
            assertEquals(1, File(path, API_KEY).listFiles()!!.size)

            connected = true
            onAvailableCallback?.invoke()
            executor.awaitExecution()

            assertEquals(1, http.requestCount)
            assertEquals(0, sut.currentRetryCountForTesting)
            assertEquals(0, sut.dequeList.size)
            assertEquals(0, File(path, API_KEY).listFiles()!!.size)
        } finally {
            sut.stop()
            sut.clear()
            executor.shutdownAndAwaitTermination()
            http.shutdown()
        }
    }

    @Test
    fun `retryable HTTP exhaustion retains a bounded queue and later success drains it`() {
        val http = mockHttp(response = MockResponse().setResponseCode(503).setBody("error"))
        http.enqueue(MockResponse().setResponseCode(503).setBody("error"))
        http.enqueue(MockResponse().setBody(""))
        val fakeCurrentTime = FakePostHogDateProvider()
        fakeCurrentTime.setAddSecondsToCurrentDate(parseISO8601Date("1970-09-20T11:58:49.000Z")!!)
        val path = tmpDir.newFolder().absolutePath
        val sut =
            getSut(
                host = http.url("/").toString(),
                storagePrefix = path,
                flushAt = 100,
                maxQueueSize = 2,
                maxRetries = 0,
                dateProvider = fakeCurrentTime,
            )

        try {
            sut.add(generateEvent("first", givenUuuid = UUID.randomUUID()))
            sut.add(generateEvent("second", givenUuuid = UUID.randomUUID()))
            executor.awaitExecution()
            val firstFile = sut.dequeList.first()

            repeat(2) {
                sut.flush()
                executor.awaitExecution()

                assertEquals(2, sut.dequeList.size)
                assertEquals(2, File(path, API_KEY).listFiles()!!.size)
            }

            sut.add(generateEvent("replacement", givenUuuid = UUID.randomUUID()))
            executor.awaitExecution()

            assertEquals(2, sut.dequeList.size)
            assertFalse(sut.dequeList.contains(firstFile))
            assertFalse(firstFile.exists())
            assertEquals(2, File(path, API_KEY).listFiles()!!.size)

            sut.flush()
            executor.awaitExecution()

            assertEquals(3, http.requestCount)
            assertEquals(0, sut.dequeList.size)
            assertEquals(0, File(path, API_KEY).listFiles()!!.size)
        } finally {
            sut.clear()
            executor.shutdownAndAwaitTermination()
            http.shutdown()
        }
    }

    @Test
    fun `retry after remains authoritative beyond the exponential backoff cap`() {
        val http = mockHttp(response = MockResponse().setResponseCode(429).setHeader("Retry-After", "120").setBody("error"))
        var scheduledDelay = 0
        val dateProvider =
            object : PostHogDateProvider {
                override fun currentDate() = Date()

                override fun addSecondsToCurrentDate(seconds: Int): Date {
                    scheduledDelay = seconds
                    return Date(System.currentTimeMillis() + seconds * 1000L)
                }

                override fun currentTimeMillis() = System.currentTimeMillis()

                override fun nanoTime() = System.nanoTime()
            }
        val path = tmpDir.newFolder().absolutePath
        val sut =
            getSut(
                host = http.url("/").toString(),
                storagePrefix = path,
                flushAt = 1,
                dateProvider = dateProvider,
            )

        try {
            sut.add(generateEvent())
            executor.awaitExecution()

            assertEquals(120, scheduledDelay)
            assertEquals(1, sut.dequeList.size)
        } finally {
            sut.clear()
            executor.shutdownAndAwaitTermination()
            http.shutdown()
        }
    }

    @Test
    fun `generic transport IO failures retain files beyond max retries and recover`() {
        val http = mockHttp()
        val failedAttempts = 3
        val attempts = AtomicInteger()
        val httpClient =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    if (attempts.incrementAndGet() <= failedAttempts) {
                        throw IOException("connection reset")
                    }
                    chain.proceed(chain.request())
                }.build()
        val fakeCurrentTime = FakePostHogDateProvider()
        fakeCurrentTime.setAddSecondsToCurrentDate(parseISO8601Date("1970-09-20T11:58:49.000Z")!!)
        val path = tmpDir.newFolder().absolutePath
        val sut =
            getSut(
                host = http.url("/").toString(),
                storagePrefix = path,
                flushAt = 100,
                maxRetries = 0,
                dateProvider = fakeCurrentTime,
                httpClient = httpClient,
            )

        try {
            sut.add(generateEvent())
            executor.awaitExecution()

            repeat(failedAttempts) {
                sut.flush()
                executor.awaitExecution()

                assertEquals(1, sut.dequeList.size)
                assertEquals(1, File(path, API_KEY).listFiles()!!.size)
            }
            assertEquals(0, http.requestCount)

            sut.flush()
            executor.awaitExecution()

            assertEquals(failedAttempts + 1, attempts.get())
            assertEquals(1, http.requestCount)
            assertEquals(0, sut.dequeList.size)
            assertEquals(0, File(path, API_KEY).listFiles()!!.size)
        } finally {
            sut.clear()
            executor.shutdownAndAwaitTermination()
            http.shutdown()
        }
    }

    @Test
    fun `duplicate payload UUIDs create distinct durable queue entries`() {
        val http = mockHttp()
        val path = tmpDir.newFolder().absolutePath
        val sut = getSut(host = http.url("/").toString(), storagePrefix = path)
        val payloadUuid = UUID.randomUUID()
        val event = generateEvent("same", givenUuuid = payloadUuid)

        try {
            sut.add(event)
            sut.add(event)
            executor.awaitExecution()

            assertEquals(2, sut.dequeList.size)
            assertEquals(2, sut.dequeList.map { it.name }.toSet().size)
            assertEquals(2, File(path, API_KEY).listFiles()!!.size)
        } finally {
            sut.clear()
            executor.shutdownAndAwaitTermination()
            http.shutdown()
        }
    }

    @Test
    fun `successful in-flight batch removes exact entries after full queue replacement`() {
        val queueExecutor = Executors.newFixedThreadPool(2, PostHogThreadFactory("ConcurrentQueueTest"))
        val path = tmpDir.newFolder().absolutePath
        val config =
            PostHogConfig(API_KEY).apply {
                storagePrefix = path
                maxQueueSize = 2
                maxBatchSize = 2
                flushAt = 100
            }
        val initialRecordsWritten = CountDownLatch(2)
        val replacementWritten = CountDownLatch(1)
        val sendStarted = CountDownLatch(1)
        val releaseSend = CountDownLatch(1)
        val sendAttempts = AtomicInteger()
        val sentRecords = Collections.synchronizedList(mutableListOf<String>())
        val spec =
            EndpointSpec(
                recordsLabel = "records",
                storagePrefix = path,
                initialCap = { it.maxBatchSize },
                initialFlushAt = { it.flushAt },
                maxQueueSize = { it.maxQueueSize },
                flushIntervalSeconds = { it.flushIntervalSeconds },
                encode = { record, stream ->
                    stream.write(record.toByteArray())
                    if (record == "replacement") {
                        replacementWritten.countDown()
                    } else {
                        initialRecordsWritten.countDown()
                    }
                },
                decode = { stream -> String(stream.readBytes()) },
                describe = { it },
                send = { records ->
                    if (sendAttempts.incrementAndGet() == 1) {
                        sentRecords.addAll(records)
                        sendStarted.countDown()
                        check(releaseSend.await(5, TimeUnit.SECONDS))
                    } else {
                        throw IOException("retain replacement after its later send attempt")
                    }
                },
                isRetriableStatusCode = { false },
            )
        val sut = PostHogQueue(config, spec, queueExecutor)

        try {
            sut.add("first")
            sut.add("second")
            assertTrue(initialRecordsWritten.await(5, TimeUnit.SECONDS))
            val initialFiles = sut.dequeList.toSet()
            assertEquals(2, initialFiles.size)

            sut.flush()
            assertTrue(sendStarted.await(5, TimeUnit.SECONDS))

            sut.add("replacement")
            assertTrue(replacementWritten.await(5, TimeUnit.SECONDS))
            val replacementFile = sut.dequeList.single { it !in initialFiles }

            releaseSend.countDown()
            assertTrue {
                waitUntil {
                    sut.currentRetryCountForTesting == 1 && sut.dequeList == listOf(replacementFile)
                }
            }

            assertEquals(2, sendAttempts.get())
            assertEquals(setOf("first", "second"), sentRecords.toSet())
            assertEquals("replacement", replacementFile.readText())
            assertTrue(replacementFile.exists())
        } finally {
            releaseSend.countDown()
            sut.clear()
            queueExecutor.shutdownAndAwaitTermination()
        }
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
        val limits = initialBatchLimits(config)

        assertFalse(deleteFilesIfAPIError(e, limits, actualBatchSize = limits.cap, logger = config.logger))
        assertEquals(limits.cap, 25) // default 50
        assertEquals(limits.flushAt, 10) // default 20
        assertEquals(config.maxBatchSize, 50) // unchanged
        assertEquals(config.flushAt, 20) // unchanged
    }

    @Test
    fun `halves cap from actual batch size when smaller than configured cap`() {
        val e = PostHogApiError(413, "", null)
        val config = PostHogConfig(API_KEY)
        val limits = initialBatchLimits(config) // cap = 50

        assertFalse(deleteFilesIfAPIError(e, limits, actualBatchSize = 10, logger = config.logger))
        assertEquals(limits.cap, 5) // halved from min(50, 10) = 10, not from 50
    }

    @Test
    fun `clamps flushAt to cap after halving so we don't queue more than a batch`() {
        // 413 on a tiny batch shrinks cap aggressively while flushAt would only halve.
        // Without clamping, flushAt could exceed cap and we'd buffer more events than
        // we can ever send in a single batch.
        val e = PostHogApiError(413, "", null)
        val config =
            PostHogConfig(API_KEY).apply {
                maxBatchSize = 50
                flushAt = 20
            }
        val limits = initialBatchLimits(config)

        assertFalse(deleteFilesIfAPIError(e, limits, actualBatchSize = 2, logger = config.logger))
        assertEquals(limits.cap, 1) // min(50, 2) / 2 = 1
        assertEquals(limits.flushAt, 1) // would be 10 without the clamp
    }

    @Test
    fun `halves cap repeatedly across multiple 413s through the queue flush flow`() {
        // End-to-end: each successive flush() against a 413 should observe a smaller
        // cap on the next attempt, proving takeFiles() reads batchLimits.cap and
        // not config.maxBatchSize.
        val http = mockHttp(total = 2, response = MockResponse().setResponseCode(413).setBody(""))
        val url = http.url("/")

        val fakeCurrentTime = FakePostHogDateProvider()
        // pause time pinned to the past so 413's calculated backoff never blocks
        fakeCurrentTime.setAddSecondsToCurrentDate(parseISO8601Date("1970-09-20T11:58:49.000Z")!!)

        // flushAt high so add() doesn't auto-flush — drive flushes manually
        val sut =
            getSut(
                host = url.toString(),
                flushAt = 100,
                dateProvider = fakeCurrentTime,
                maxBatchSize = 4,
            )

        for (i in 0 until 4) {
            sut.add(generateEvent("event$i", givenUuuid = UUID.randomUUID()))
        }
        executor.awaitExecution()
        assertEquals(4, sut.dequeList.size)
        assertEquals(4, sut.currentBatchCapForTesting)

        // First flush: batch=4 → 413 → cap halves to 2, batch retained.
        sut.flush()
        executor.awaitExecution()
        assertEquals(2, sut.currentBatchCapForTesting)
        assertEquals(2, sut.currentFlushAtForTesting)
        assertEquals(4, sut.dequeList.size)

        // Second flush: batch=2 (using the new, smaller cap) → 413 → cap halves to 1.
        sut.flush()
        executor.awaitExecution()
        assertEquals(1, sut.currentBatchCapForTesting)
        assertEquals(1, sut.currentFlushAtForTesting)
        assertEquals(4, sut.dequeList.size)

        sut.clear()
        executor.shutdownAndAwaitTermination()
    }

    @Test
    fun `delete files if batch is min already`() {
        val e = PostHogApiError(413, "", null)
        val config =
            PostHogConfig(API_KEY).apply {
                maxBatchSize = 1
                flushAt = 1
            }
        val limits = initialBatchLimits(config)

        assertTrue(deleteFilesIfAPIError(e, limits, actualBatchSize = 1, logger = config.logger))
        assertEquals(limits.cap, 1)
        assertEquals(limits.flushAt, 1)
    }

    @Test
    fun `delete files if 413 affects a single record with larger configured cap`() {
        val e = PostHogApiError(413, "", null)
        val config = PostHogConfig(API_KEY)
        val limits = initialBatchLimits(config)

        assertTrue(deleteFilesIfAPIError(e, limits, actualBatchSize = 1, logger = config.logger))
        assertEquals(50, limits.cap)
        assertEquals(20, limits.flushAt)
    }

    @Test
    fun `delete files if errored`() {
        val e = PostHogApiError(400, "", null)
        val config = PostHogConfig(API_KEY)
        val limits = initialBatchLimits(config)

        assertTrue(deleteFilesIfAPIError(e, limits, actualBatchSize = limits.cap, logger = config.logger))
    }

    @Test
    fun `retries on 408`() {
        val e = PostHogApiError(408, "", null)
        val config = PostHogConfig(API_KEY)
        val limits = initialBatchLimits(config)

        assertFalse(deleteFilesIfAPIError(e, limits, actualBatchSize = limits.cap, logger = config.logger))
    }

    @Test
    fun `retries on 500`() {
        val e = PostHogApiError(500, "", null)
        val config = PostHogConfig(API_KEY)
        val limits = initialBatchLimits(config)

        assertFalse(deleteFilesIfAPIError(e, limits, actualBatchSize = limits.cap, logger = config.logger))
    }

    @Test
    fun `retries on 502`() {
        val e = PostHogApiError(502, "", null)
        val config = PostHogConfig(API_KEY)
        val limits = initialBatchLimits(config)

        assertFalse(deleteFilesIfAPIError(e, limits, actualBatchSize = limits.cap, logger = config.logger))
    }

    @Test
    fun `retries on 429`() {
        val e = PostHogApiError(429, "", null)
        val config = PostHogConfig(API_KEY)
        val limits = initialBatchLimits(config)

        assertFalse(deleteFilesIfAPIError(e, limits, actualBatchSize = limits.cap, logger = config.logger))
    }

    @Test
    fun `retries on 504`() {
        val e = PostHogApiError(504, "", null)
        val config = PostHogConfig(API_KEY)
        val limits = initialBatchLimits(config)

        assertFalse(deleteFilesIfAPIError(e, limits, actualBatchSize = limits.cap, logger = config.logger))
    }

    @Test
    fun `retries on 503`() {
        val e = PostHogApiError(503, "", null)
        val config = PostHogConfig(API_KEY)
        val limits = initialBatchLimits(config)

        assertFalse(deleteFilesIfAPIError(e, limits, actualBatchSize = limits.cap, logger = config.logger))
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
    fun `reload evicts oldest cached files beyond queue capacity`() {
        val http = mockHttp()
        val path = tmpDir.newFolder().absolutePath
        val dir = File(path, API_KEY)
        dir.mkdirs()
        val eventContent = File("src/test/resources/json/basic-event.json").readText()
        val cachedFiles =
            (1..3).map { index ->
                File(dir, "${UUID.randomUUID()}.event").apply {
                    writeText(eventContent)
                    setLastModified(System.currentTimeMillis() - (4 - index) * 1000L)
                }
            }
        val sut = getSut(host = http.url("/").toString(), storagePrefix = path, maxQueueSize = 2)

        try {
            sut.reloadFromDisk()

            assertEquals(2, sut.dequeList.size)
            assertFalse(cachedFiles.first().exists())
            assertEquals(cachedFiles.drop(1), sut.dequeList)
            assertEquals(2, dir.listFiles()!!.size)
        } finally {
            sut.clear()
            executor.shutdownAndAwaitTermination()
            http.shutdown()
        }
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

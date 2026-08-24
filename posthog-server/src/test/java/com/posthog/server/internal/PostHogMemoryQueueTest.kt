package com.posthog.server.internal

import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import com.posthog.internal.PostHogApi
import com.posthog.internal.PostHogApiEndpoint
import com.posthog.internal.PostHogDateProvider
import com.posthog.internal.PostHogDeviceDateProvider
import com.posthog.internal.PostHogNetworkStatus
import com.posthog.internal.PostHogThreadFactory
import com.posthog.server.awaitExecution
import com.posthog.server.createMockHttp
import com.posthog.server.generateEvent
import com.posthog.server.shutdownAndAwaitTermination
import com.posthog.server.unGzip
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test

internal class PostHogMemoryQueueTest {
    private val executor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("Test"))

    private class MutableDateProvider(
        var nowMillis: Long = 0,
    ) : PostHogDateProvider {
        override fun currentDate(): Date = Date(nowMillis)

        override fun addSecondsToCurrentDate(seconds: Int): Date = Date(nowMillis + seconds * 1000L)

        override fun currentTimeMillis(): Long = nowMillis

        override fun nanoTime(): Long = nowMillis * 1_000_000L
    }

    private fun getSut(
        host: String,
        maxQueueSize: Int = 1000,
        flushAt: Int = 20,
        flushIntervalSeconds: Int = 10,
        dateProvider: PostHogDateProvider = PostHogDeviceDateProvider(),
        maxBatchSize: Int = 50,
        networkStatus: PostHogNetworkStatus? = null,
        retryDelaySeconds: Int = 5,
        fatalFlushTimeoutMs: Long = 2_000L,
    ): PostHogMemoryQueue {
        val config =
            PostHogConfig("some_api_key", host).apply {
                this.maxQueueSize = maxQueueSize
                this.flushAt = flushAt
                this.networkStatus = networkStatus
                this.maxBatchSize = maxBatchSize
                this.dateProvider = dateProvider
                this.flushIntervalSeconds = flushIntervalSeconds
            }
        val api = PostHogApi(config)
        return PostHogMemoryQueue(
            config,
            api,
            PostHogApiEndpoint.BATCH,
            executor = executor,
            retryDelaySeconds = retryDelaySeconds,
            fatalFlushTimeoutMs = fatalFlushTimeoutMs,
        )
    }

    // A fatal $exception event, i.e. one PostHogEvent.isFatalExceptionEvent() marks for the
    // blocking crash path in add().
    private fun generateFatalEvent(): PostHogEvent {
        val event = generateEvent("\$exception")
        event.properties?.put("\$exception_level", "fatal")
        return event
    }

    @Test
    fun `adds a single event`() {
        val http = createMockHttp()
        val url = http.url("/")
        http.enqueue(MockResponse().setBody("{}"))
        val sut = getSut(url.toString(), flushAt = 1)
        val event = generateEvent()
        sut.add(event)
        executor.awaitExecution()

        val request = http.takeRequest()
        assertEquals("POST", request.method)

        http.shutdown()
        executor.shutdownAndAwaitTermination()
    }

    @Test
    fun `does not flush empty queue`() {
        val http = createMockHttp()
        val sut = getSut(http.url("/").toString(), flushAt = 1)
        sut.flush()
        executor.awaitExecution()

        assertEquals(0, http.requestCount)

        http.shutdown()
        executor.shutdownAndAwaitTermination()
    }

    @Test
    fun `flushes if queue is above threshold`() {
        val http = createMockHttp(MockResponse().setBody("{}"))
        val sut = getSut(http.url("/").toString(), flushAt = 2)
        val event = generateEvent()
        sut.add(event)
        sut.add(event.copy())
        executor.awaitExecution()

        val request = http.takeRequest()
        assertEquals("POST", request.method)

        http.shutdown()
        executor.shutdownAndAwaitTermination()
    }

    @Test
    fun `does not flush if queue is below threshold`() {
        val http = createMockHttp()
        val sut = getSut(http.url("/").toString(), flushAt = 2)
        val event = generateEvent()
        sut.add(event)
        executor.awaitExecution()

        assertEquals(0, http.requestCount)

        http.shutdown()
        executor.shutdownAndAwaitTermination()
    }

    @Test
    fun `discards oldest event when queue is full`() {
        val http = createMockHttp(MockResponse().setBody("{}"))
        val sut = getSut(http.url("/").toString(), maxQueueSize = 2, flushAt = 5)

        sut.add(generateEvent("event1"))
        sut.add(generateEvent("event2"))
        sut.add(generateEvent("event3")) // Should discard event1
        executor.awaitExecution()

        sut.flush()

        val request = http.takeRequest()
        val body = request.body.unGzip()

        assertEquals("POST", request.method)
        assertTrue("Body should contain event2", body.contains("event2"))
        assertTrue("Body should contain event3", body.contains("event3"))
        assertFalse("Body should not contain event1", body.contains("event1"))

        http.shutdown()
        executor.shutdownAndAwaitTermination()
    }

    @Test
    fun `respects max batch size`() {
        val http = createMockHttp(MockResponse().setBody("{}"), MockResponse().setBody("{}"))
        val sut = getSut(http.url("/").toString(), maxBatchSize = 2, flushAt = 3)
        val event = generateEvent()

        sut.add(event)
        sut.add(event.copy())
        sut.add(event.copy())
        executor.awaitExecution()

        // Should make one request with 2 events (maxBatchSize)
        val request1 = http.takeRequest()
        assertEquals("POST", request1.method)

        // Flush the remaining event
        sut.flush()
        executor.awaitExecution()

        val request2 = http.takeRequest()
        assertEquals("POST", request2.method)

        http.shutdown()
        executor.shutdownAndAwaitTermination()
    }

    @Test
    fun `flush drains all pending batches`() {
        val http =
            createMockHttp(
                MockResponse().setBody("{}"),
                MockResponse().setBody("{}"),
                MockResponse().setBody("{}"),
            )
        val sut = getSut(http.url("/").toString(), maxBatchSize = 2, flushAt = 10)
        val event = generateEvent()

        repeat(5) {
            sut.add(event.copy())
        }
        executor.awaitExecution()

        sut.flush()
        executor.awaitExecution()

        assertEquals(3, http.requestCount)

        http.shutdown()
        executor.shutdownAndAwaitTermination()
    }

    @Test
    fun `does not flush if network is not connected`() {
        val http = createMockHttp()
        val sut =
            getSut(
                http.url("/").toString(),
                flushAt = 1,
                networkStatus =
                    object : PostHogNetworkStatus {
                        override fun isConnected() = false
                    },
            )

        sut.add(generateEvent())
        executor.awaitExecution()

        assertEquals(0, http.requestCount)

        http.shutdown()
        executor.shutdownAndAwaitTermination()
    }

    @Test
    fun `explicit flush does not flush if network is not connected`() {
        val http = createMockHttp(MockResponse().setBody("{}"))
        var connected = false
        val sut =
            getSut(
                http.url("/").toString(),
                flushAt = 10,
                networkStatus =
                    object : PostHogNetworkStatus {
                        override fun isConnected() = connected
                    },
            )

        sut.add(generateEvent())
        executor.awaitExecution()

        sut.flush()
        executor.awaitExecution()

        assertEquals(0, http.requestCount)

        connected = true
        sut.flush()
        executor.awaitExecution()

        assertEquals(1, http.requestCount)

        http.shutdown()
        executor.shutdownAndAwaitTermination()
    }

    @Test
    fun `explicit flush stops draining if network disconnects between batches`() {
        var connected = true
        val http = createMockHttp()
        http.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    connected = false
                    return MockResponse().setBody("{}")
                }
            }
        val sut =
            getSut(
                http.url("/").toString(),
                flushAt = 10,
                maxBatchSize = 2,
                networkStatus =
                    object : PostHogNetworkStatus {
                        override fun isConnected() = connected
                    },
            )

        repeat(3) {
            sut.add(generateEvent())
        }
        executor.awaitExecution()

        sut.flush()
        executor.awaitExecution()

        assertEquals(1, http.requestCount)

        connected = true
        sut.flush()
        executor.awaitExecution()

        assertEquals(2, http.requestCount)

        http.shutdown()
        executor.shutdownAndAwaitTermination()
    }

    @Test
    fun `explicit flush waits for retry pause to expire`() {
        val http =
            createMockHttp(
                MockResponse().setResponseCode(500),
                MockResponse().setBody("{}"),
            )
        val dateProvider = MutableDateProvider()
        val sut =
            getSut(
                http.url("/").toString(),
                flushAt = 10,
                dateProvider = dateProvider,
                retryDelaySeconds = 5,
            )

        sut.add(generateEvent())
        executor.awaitExecution()

        sut.flush()
        executor.awaitExecution()
        assertEquals(1, http.requestCount)

        sut.flush()
        executor.awaitExecution()
        assertEquals(1, http.requestCount)

        dateProvider.nowMillis += 5_000
        sut.flush()
        executor.awaitExecution()
        assertEquals(2, http.requestCount)

        http.shutdown()
        executor.shutdownAndAwaitTermination()
    }

    @Test
    fun `retries on network error`() {
        val http = createMockHttp(MockResponse().setResponseCode(500))
        val sut =
            getSut(
                http.url("/").toString(),
                flushAt = 1,
                flushIntervalSeconds = 1,
                retryDelaySeconds = 1,
            )
        val event = generateEvent()

        sut.start() // Start the timer for retries
        sut.add(event)
        executor.awaitExecution()

        assertEquals(1, http.requestCount)

        // Retry will be allowed after one second.
        // Flush will occur every second.
        // We wait a bit more than 2 seconds to ensure both have occurred.
        Thread.sleep(2100)
        executor.awaitExecution()

        // Should have made both requests (original + retry)
        assertEquals(2, http.requestCount)

        http.shutdown()
        executor.shutdownAndAwaitTermination()
    }

    @Test
    fun `clears queue`() {
        val http = createMockHttp()
        val sut = getSut(http.url("/").toString(), flushAt = 10)
        val event = generateEvent()
        sut.add(event)
        sut.add(event.copy())
        executor.awaitExecution()

        sut.clear()
        executor.awaitExecution()

        sut.flush()
        executor.awaitExecution()

        // No requests should be made after clearing
        assertEquals(0, http.requestCount)

        http.shutdown()
        executor.shutdownAndAwaitTermination()
    }

    @Test
    fun `starts and stops timer`() {
        val http = createMockHttp()
        val sut = getSut(http.url("/").toString())
        sut.start()
        sut.stop()
        // If we get here without deadlock, the timer management works
        assertTrue(true)

        http.shutdown()
        executor.shutdownAndAwaitTermination()
    }

    @Test
    fun `a fatal exception event is sent before add returns`() {
        val http = createMockHttp(MockResponse().setBody("{}"))
        val sut = getSut(http.url("/").toString(), flushAt = 100)

        // add() blocks on the fatal path, so the request must already be there when it returns —
        // no executor await, no waiting takeRequest.
        sut.add(generateFatalEvent())

        assertEquals(1, http.requestCount)
        val body = http.takeRequest().body.unGzip()
        assertTrue("Body should contain the fatal exception event", body.contains("\$exception"))

        http.shutdown()
        executor.shutdownAndAwaitTermination()
    }

    @Test
    fun `the fatal path is ordered behind a pending enqueue instead of racing it`() {
        val http = createMockHttp(MockResponse().setBody("{}"))
        val sut = getSut(http.url("/").toString(), flushAt = 100)

        // Park the single queue thread so the enqueue below is provably still pending: the
        // crash-path race, made deterministic without any sleeping.
        val blocked = CountDownLatch(1)
        executor.execute { blocked.await() }

        sut.add(generateEvent("earlier_event"))

        // The fatal add has to wait for the parked thread, so nothing can have been sent yet.
        val sender = Thread { sut.add(generateFatalEvent()) }
        sender.start()
        assertEquals(0, http.requestCount)

        blocked.countDown()
        sender.join(5_000)
        assertFalse("Expected the fatal add to return once the queue drained", sender.isAlive)

        // Both the pending earlier event and the fatal event went out.
        val body = http.takeRequest().body.unGzip()
        assertTrue("Body should contain the earlier pending event", body.contains("earlier_event"))
        assertTrue("Body should contain the fatal exception event", body.contains("\$exception"))

        http.shutdown()
        executor.shutdownAndAwaitTermination()
    }

    @Test
    fun `the fatal path drains a backlog larger than maxBatchSize so the crash event is not stranded`() {
        // 5 older events + the fatal one at maxBatchSize=2 need 3 sequential batches; a single
        // bounded batch would send only the oldest 2 and strand the crash event (FIFO puts it last).
        val http =
            createMockHttp(
                MockResponse().setBody("{}"),
                MockResponse().setBody("{}"),
                MockResponse().setBody("{}"),
            )
        val sut = getSut(http.url("/").toString(), flushAt = 100, maxBatchSize = 2)

        repeat(5) { sut.add(generateEvent("backlog_event_$it")) }
        executor.awaitExecution()
        assertEquals(0, http.requestCount)

        sut.add(generateFatalEvent())

        assertEquals(3, http.requestCount)
        // Front-inserted, so the crash event rides the very first batch instead of trailing the
        // backlog.
        val firstBody = http.takeRequest().body.unGzip()
        assertTrue("The crash event must be in the first drained batch", firstBody.contains("\$exception"))

        http.shutdown()
        executor.shutdownAndAwaitTermination()
    }

    @Test
    fun `the fatal event gets its wire attempt even when the first batch fails`() {
        // A retriable failure requeues the batch and stops the drain (no retry loops on a crashing
        // process). With the fatal event appended last it would never have reached the wire; front
        // insertion puts it in that first, only attempt.
        val http = createMockHttp(MockResponse().setResponseCode(500))
        val sut = getSut(http.url("/").toString(), flushAt = 100, maxBatchSize = 2)

        repeat(5) { sut.add(generateEvent("backlog_event_$it")) }
        executor.awaitExecution()

        sut.add(generateFatalEvent())

        assertEquals(1, http.requestCount)
        val body = http.takeRequest().body.unGzip()
        assertTrue("The failed attempt must have carried the crash event", body.contains("\$exception"))

        http.shutdown()
        executor.shutdownAndAwaitTermination()
    }

    @Test
    fun `the fatal path waits out a flush already in progress instead of giving up`() {
        // A periodic-timer flush racing the crash used to make the fatal drain a silent no-op: the
        // drain saw no progress and returned with the crash event still queued.
        val gate = CountDownLatch(1)
        val firstRequestReceived = CountDownLatch(1)
        val requestBodies = CopyOnWriteArrayList<String>()
        val http = MockWebServer()
        http.dispatcher =
            object : Dispatcher() {
                private val requests = AtomicInteger(0)

                override fun dispatch(request: RecordedRequest): MockResponse {
                    requestBodies.add(request.body.unGzip())
                    if (requests.getAndIncrement() == 0) {
                        firstRequestReceived.countDown()
                        gate.await(5, TimeUnit.SECONDS)
                    }
                    return MockResponse().setBody("{}")
                }
            }
        http.start()
        val sut = getSut(http.url("/").toString(), flushAt = 100)

        sut.add(generateEvent("pre_event"))
        executor.awaitExecution()

        // A stand-in for the periodic timer: flush() runs inline on this thread and parks in the
        // gated request while holding the isFlushing flag.
        val flusher = Thread { sut.flush() }
        flusher.start()
        assertTrue(
            "Expected the concurrent flush to reach the server and hold the flag",
            firstRequestReceived.await(5, TimeUnit.SECONDS),
        )

        val sender = Thread { sut.add(generateFatalEvent()) }
        sender.start()
        // Let the drain observe the held flag before the gate opens.
        Thread.sleep(100)
        gate.countDown()

        sender.join(5_000)
        flusher.join(5_000)
        assertFalse("Expected the fatal add to return", sender.isAlive)

        assertTrue(
            "The crash event must be sent once the concurrent flush finished, got: $requestBodies",
            requestBodies.any { it.contains("\$exception") },
        )

        http.shutdown()
        executor.shutdownAndAwaitTermination()
    }

    @Test
    fun `a fatal event parked at the head after a failed attempt is not evicted by capacity trimming`() {
        // First attempt fails, requeueing the fatal event at the deque head; in a process that
        // survived, later ordinary captures reaching maxQueueSize used to evict the head as the
        // "oldest" event — discarding the crash before any retry could send it.
        val http = createMockHttp(MockResponse().setResponseCode(500), MockResponse().setBody("{}"))
        val sut = getSut(http.url("/").toString(), flushAt = 100, maxQueueSize = 2, retryDelaySeconds = 0)

        sut.add(generateFatalEvent())
        assertEquals(1, http.requestCount)

        // Two ordinary events on a full queue: eviction must take the non-fatal one.
        sut.add(generateEvent("ordinary_1"))
        sut.add(generateEvent("ordinary_2"))
        executor.awaitExecution()

        sut.flush()
        assertEquals(2, http.requestCount)
        http.takeRequest() // the failed first attempt
        val retryBody = http.takeRequest().body.unGzip()
        assertTrue("The retried batch must still contain the crash event", retryBody.contains("\$exception"))

        http.shutdown()
        executor.shutdownAndAwaitTermination()
    }

    @Test
    fun `maxQueueSize stays a hard bound even when every queued event is fatal`() {
        // Persistent send failures in a surviving process can park fatal events; the fatal-eviction
        // preference must not turn the cap into unbounded growth — with only fatal events queued,
        // the oldest one is evicted anyway.
        val http =
            createMockHttp(
                MockResponse().setResponseCode(500),
                MockResponse().setResponseCode(500),
                MockResponse().setBody("{}"),
            )
        val sut = getSut(http.url("/").toString(), flushAt = 100, maxQueueSize = 1, retryDelaySeconds = 0)

        val fatalA = generateFatalEvent().also { it.properties?.put("marker", "fatal_a") }
        val fatalB = generateFatalEvent().also { it.properties?.put("marker", "fatal_b") }

        sut.add(fatalA)
        sut.add(fatalB)
        assertEquals(2, http.requestCount)
        http.takeRequest()
        http.takeRequest()

        // Only the newer fatal event survived the cap; the retry sends it alone.
        sut.flush()
        assertEquals(3, http.requestCount)
        val retryBody = http.takeRequest().body.unGzip()
        assertTrue("The newest fatal event must survive", retryBody.contains("fatal_b"))
        assertFalse("The evicted fatal event must be gone", retryBody.contains("fatal_a"))

        http.shutdown()
        executor.shutdownAndAwaitTermination()
    }

    @Test
    fun `ordinary traffic is dropped rather than displacing a parked fatal event`() {
        val http = createMockHttp(MockResponse().setResponseCode(500), MockResponse().setBody("{}"))
        val sut = getSut(http.url("/").toString(), flushAt = 100, maxQueueSize = 1, retryDelaySeconds = 0)

        val fatal = generateFatalEvent().also { it.properties?.put("marker", "fatal_a") }
        sut.add(fatal)
        assertEquals(1, http.requestCount)
        http.takeRequest()

        // The queue is full of exactly one parked crash report; the ordinary event loses.
        sut.add(generateEvent("ordinary_1"))
        executor.awaitExecution()

        sut.flush()
        assertEquals(2, http.requestCount)
        val retryBody = http.takeRequest().body.unGzip()
        assertTrue("The parked fatal event must survive ordinary traffic", retryBody.contains("fatal_a"))
        assertFalse("The ordinary event must have been dropped", retryBody.contains("ordinary_1"))

        http.shutdown()
        executor.shutdownAndAwaitTermination()
    }

    @Test
    fun `fatal-on-fatal eviction removes the oldest crash report, not the newest`() {
        // Fatal records are front-inserted (head is NEWEST), so all-fatal trimming must take the
        // tail; taking the head would silently discard the most recent crash.
        val http =
            createMockHttp(
                MockResponse().setResponseCode(500),
                MockResponse().setResponseCode(500),
                MockResponse().setResponseCode(500),
                MockResponse().setBody("{}"),
            )
        val sut = getSut(http.url("/").toString(), flushAt = 100, maxQueueSize = 2, retryDelaySeconds = 0)

        listOf("fatal_a", "fatal_b", "fatal_c").forEach { marker ->
            sut.add(generateFatalEvent().also { it.properties?.put("marker", marker) })
        }
        assertEquals(3, http.requestCount)
        repeat(3) { http.takeRequest() }

        sut.flush()
        assertEquals(4, http.requestCount)
        val retryBody = http.takeRequest().body.unGzip()
        assertTrue("The newest crash reports must survive", retryBody.contains("fatal_b"))
        assertTrue("The newest crash reports must survive", retryBody.contains("fatal_c"))
        assertFalse("The oldest crash report is the one trimmed", retryBody.contains("fatal_a"))

        http.shutdown()
        executor.shutdownAndAwaitTermination()
    }

    @Test
    fun `the fatal path still flushes when the calling thread is already interrupted`() {
        val http = createMockHttp(MockResponse().setBody("{}"))
        val sut = getSut(http.url("/").toString(), flushAt = 100)

        // A crash on a thread some shutdown just interrupted must not lose its flush.
        Thread.currentThread().interrupt()
        try {
            sut.add(generateFatalEvent())
            assertEquals(1, http.requestCount)
            assertTrue(
                "Expected the interrupt flag to be restored for the caller",
                Thread.currentThread().isInterrupted,
            )
        } finally {
            // Never leak the interrupt into whatever runs next on this thread.
            Thread.interrupted()
        }

        http.shutdown()
        executor.shutdownAndAwaitTermination()
    }

    @Test
    fun `the fatal path gives up after the timeout instead of hanging the crashing thread`() {
        val http = createMockHttp()
        val sut = getSut(http.url("/").toString(), flushAt = 100, fatalFlushTimeoutMs = 200)

        // Occupy the single queue thread so the fatal enqueue-and-drain task can never run.
        val blocked = CountDownLatch(1)
        executor.execute { blocked.await() }

        val startedAt = System.nanoTime()
        sut.add(generateFatalEvent())
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue("Expected to wait for the timeout, waited $elapsedMs ms", elapsedMs >= 200)
        assertTrue("Expected to return right after the timeout, waited $elapsedMs ms", elapsedMs < 2_000)
        assertEquals(0, http.requestCount)

        blocked.countDown()
        http.shutdown()
        executor.shutdownAndAwaitTermination()
    }
}

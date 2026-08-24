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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test

internal class PostHogMemoryQueueTest {
    private val executor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("Test"))

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
        val lastBody =
            (1..3).joinToString("\n") { http.takeRequest().body.unGzip() }
        assertTrue("The crash event must be part of the drained batches", lastBody.contains("\$exception"))

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

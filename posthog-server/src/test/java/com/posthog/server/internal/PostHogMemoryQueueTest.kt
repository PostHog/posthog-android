package com.posthog.server.internal

import com.posthog.PostHogConfig
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
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
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
        )
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
    fun `flushBlocking sends an event whose enqueue has not run yet`() {
        val http = createMockHttp(MockResponse().setBody("{}"))
        val sut = getSut(http.url("/").toString(), flushAt = 100)

        // Park the single queue thread so the enqueue below is provably still pending while the
        // caller flushes inline: the crash-path race, made deterministic without any sleeping.
        val blocked = CountDownLatch(1)
        executor.execute { blocked.await() }

        sut.add(generateEvent())

        // The inline flush cannot see the pending enqueue, so it sends nothing.
        sut.flush()
        assertEquals(0, http.requestCount)

        blocked.countDown()

        // The barrier is queued behind the enqueue on the same single thread, so the send sees it.
        assertTrue("Expected the blocking flush to complete", sut.flushBlocking(2_000))
        assertEquals(1, http.requestCount)

        http.shutdown()
        executor.shutdownAndAwaitTermination()
    }

    @Test
    fun `flushBlocking still flushes when the calling thread is already interrupted`() {
        val http = createMockHttp(MockResponse().setBody("{}"))
        val sut = getSut(http.url("/").toString(), flushAt = 100)

        sut.add(generateEvent())

        // A crash on a thread some shutdown just interrupted must not lose its flush.
        Thread.currentThread().interrupt()
        try {
            assertTrue("Expected the blocking flush to complete", sut.flushBlocking(2_000))
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
    fun `flushBlocking gives up after the timeout instead of hanging`() {
        val http = createMockHttp()
        val sut = getSut(http.url("/").toString(), flushAt = 100)

        // Occupy the single queue thread so the barrier can never run.
        val blocked = CountDownLatch(1)
        executor.execute { blocked.await() }

        sut.add(generateEvent())

        val startedAt = System.nanoTime()
        assertFalse("Expected the blocking flush to time out", sut.flushBlocking(200))
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue("Expected to wait for the timeout, waited $elapsedMs ms", elapsedMs >= 200)
        assertTrue("Expected to return right after the timeout, waited $elapsedMs ms", elapsedMs < 2_000)
        assertEquals(0, http.requestCount)

        blocked.countDown()
        http.shutdown()
        executor.shutdownAndAwaitTermination()
    }
}

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
                networkStatus = PostHogNetworkStatus { false },
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
}

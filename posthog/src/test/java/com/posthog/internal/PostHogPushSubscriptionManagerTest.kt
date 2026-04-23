package com.posthog.internal

import com.posthog.API_KEY
import com.posthog.PostHogConfig
import com.posthog.mockHttp
import com.posthog.unGzip
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class PostHogPushSubscriptionManagerTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    private val executor: ExecutorService = Executors.newSingleThreadExecutor(PostHogThreadFactory("TestPushSub"))

    @AfterTest
    fun `set down`() {
        executor.shutdownNow()
        tmpDir.root.deleteRecursively()
    }

    private fun getSut(
        http: MockWebServer,
        storagePrefix: String = tmpDir.newFolder().absolutePath,
        networkStatus: PostHogNetworkStatus? = null,
        maxRetries: Int = 3,
    ): Triple<PostHogPushSubscriptionManager, PostHogConfig, String> {
        val config =
            PostHogConfig(API_KEY, host = http.url("/").toString()).apply {
                this.storagePrefix = storagePrefix
                this.networkStatus = networkStatus
                this.maxRetries = maxRetries
            }
        val api = PostHogApi(config)
        return Triple(PostHogPushSubscriptionManager(config, api, executor), config, storagePrefix)
    }

    private fun pendingFile(storagePrefix: String): File = File(File(storagePrefix, API_KEY), "push_subscription.pending")

    private fun flush() {
        executor.submit {}.get()
    }

    @Test
    fun `register posts subscription and deletes pending file on success`() {
        val http = mockHttp()
        val (sut, _, storagePrefix) = getSut(http)

        sut.register("distinct-1", "fcm-token", "firebase-project", "android")
        flush()

        val request = http.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/push_subscriptions/", request.path)
        assertFalse(pendingFile(storagePrefix).exists())
    }

    @Test
    fun `register defers when network is disconnected and keeps pending file`() {
        val http = mockHttp()
        val offline =
            object : PostHogNetworkStatus {
                override fun isConnected() = false
            }
        val (sut, _, storagePrefix) = getSut(http, networkStatus = offline)

        sut.register("distinct-1", "fcm-token", "firebase-project", "android")
        flush()

        assertEquals(0, http.requestCount)
        assertTrue(pendingFile(storagePrefix).exists())
    }

    @Test
    fun `register deletes file on non-retryable failure`() {
        val http = mockHttp(response = MockResponse().setResponseCode(400).setBody("bad"))
        val (sut, _, storagePrefix) = getSut(http)

        sut.register("distinct-1", "fcm-token", "firebase-project", "android")
        flush()

        assertEquals(1, http.requestCount)
        assertFalse(pendingFile(storagePrefix).exists())
    }

    @Test
    fun `register keeps file after retryable failure exhausts retries`() {
        val http = mockHttp(response = MockResponse().setResponseCode(503).setBody("unavailable"))
        // maxRetries = 0 means the first failure exhausts retries immediately,
        // keeping the file for the next SDK start.
        val (sut, _, storagePrefix) = getSut(http, maxRetries = 0)

        sut.register("distinct-1", "fcm-token", "firebase-project", "android")
        flush()

        assertEquals(1, http.requestCount)
        assertTrue(pendingFile(storagePrefix).exists())
    }

    @Test
    fun `retryPending is a no-op when there is no pending file`() {
        val http = mockHttp()
        val (sut, _, _) = getSut(http)

        sut.retryPending()
        flush()

        assertEquals(0, http.requestCount)
    }

    @Test
    fun `retryPending reads pending record and posts it`() {
        val http = MockWebServer()
        http.start()
        // First response: 503 (retryable; with maxRetries=0 the file stays on disk).
        http.enqueue(MockResponse().setResponseCode(503))
        // Second response: success (the retry after "restart").
        http.enqueue(MockResponse().setBody(""))

        val (sut, _, storagePrefix) = getSut(http, maxRetries = 0)

        sut.register("distinct-1", "fcm-token", "firebase-project", "android")
        flush()
        assertTrue(pendingFile(storagePrefix).exists())

        sut.retryPending()
        flush()

        assertEquals(2, http.requestCount)
        assertFalse(pendingFile(storagePrefix).exists())
        http.shutdown()
    }

    @Test
    fun `register persists each call and deletes the file on success`() {
        val http = mockHttp(total = 2, response = MockResponse().setBody(""))
        val (sut, _, storagePrefix) = getSut(http)

        sut.register("distinct-1", "token-1", "firebase-project", "android")
        flush()
        sut.register("distinct-1", "token-2", "firebase-project", "android")
        flush()

        assertEquals(2, http.requestCount)
        val first = http.takeRequest().body.unGzip()
        val second = http.takeRequest().body.unGzip()
        assertTrue(first.contains("token-1"))
        assertTrue(second.contains("token-2"))
        assertFalse(pendingFile(storagePrefix).exists())
    }

    @Test
    fun `register writes record that roundtrips through serializer`() {
        val http = mockHttp(response = MockResponse().setResponseCode(503))
        val (sut, config, storagePrefix) = getSut(http, maxRetries = 0)

        sut.register("distinct-1", "fcm-token", "firebase-project", "android")
        flush()

        val file = pendingFile(storagePrefix)
        assertTrue(file.exists())

        val record =
            file.inputStream().use {
                config.serializer.deserialize<PostHogPushSubscriptionManager.PendingRecord?>(it.reader().buffered())
            }
        assertEquals("distinct-1", record?.distinctId)
        assertEquals("fcm-token", record?.deviceToken)
        assertEquals("firebase-project", record?.appId)
        assertEquals("android", record?.platform)
    }

    @Test
    fun `retryPending removes corrupt pending file`() {
        val http = mockHttp()
        val (sut, _, storagePrefix) = getSut(http)

        // Write a file with junk bytes that can't be deserialized.
        val file = pendingFile(storagePrefix)
        file.parentFile.mkdirs()
        file.writeText("{not valid json")

        sut.retryPending()
        flush()

        assertEquals(0, http.requestCount)
        assertFalse(file.exists())
    }

    @Test
    fun `register with null storagePrefix still attempts the request`() {
        val http = mockHttp()
        val config =
            PostHogConfig(API_KEY, host = http.url("/").toString()).apply {
                this.storagePrefix = null
            }
        val api = PostHogApi(config)
        val sut = PostHogPushSubscriptionManager(config, api, executor)

        sut.register("distinct-1", "fcm-token", "firebase-project", "android")
        flush()

        assertEquals(1, http.requestCount)
    }

    @Test
    fun `retryPending is a no-op when storagePrefix is null`() {
        val http = mockHttp()
        val config =
            PostHogConfig(API_KEY, host = http.url("/").toString()).apply {
                this.storagePrefix = null
            }
        val api = PostHogApi(config)
        val sut = PostHogPushSubscriptionManager(config, api, executor)

        sut.retryPending()
        flush()

        assertEquals(0, http.requestCount)
    }
}

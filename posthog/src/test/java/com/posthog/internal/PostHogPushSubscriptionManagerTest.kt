package com.posthog.internal

import com.posthog.API_KEY
import com.posthog.PostHogConfig
import com.posthog.PostHogEncryption
import com.posthog.mockHttp
import com.posthog.unGzip
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PostHogPushSubscriptionManagerTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    private val executor: ExecutorService = Executors.newSingleThreadExecutor(PostHogThreadFactory("TestPushSub"))

    @Volatile
    private var distinctId: String = "distinct-1"

    @AfterTest
    fun `set down`() {
        executor.shutdownNow()
        tmpDir.root.deleteRecursively()
    }

    private fun getSut(
        http: MockWebServer,
        storagePrefix: String? = tmpDir.newFolder().absolutePath,
        networkStatus: PostHogNetworkStatus? = null,
        maxRetries: Int = 3,
        encryption: PostHogEncryption? = null,
    ): Triple<PostHogPushSubscriptionManager, PostHogConfig, String?> {
        val config =
            PostHogConfig(API_KEY, host = http.url("/").toString()).apply {
                this.storagePrefix = storagePrefix
                this.networkStatus = networkStatus
                this.maxRetries = maxRetries
                this.encryption = encryption
            }
        val api = PostHogApi(config)
        val manager = PostHogPushSubscriptionManager(config, api, executor) { distinctId }
        return Triple(manager, config, storagePrefix)
    }

    private fun pendingFile(storagePrefix: String): File = File(File(File(storagePrefix, "push"), API_KEY), "push_subscription.pending")

    private fun flush() {
        executor.submit {}.get()
    }

    private fun readRecord(
        config: PostHogConfig,
        file: File,
    ): PostHogPushSubscriptionManager.PendingRecord? {
        val input = config.encryption?.decrypt(file.inputStream()) ?: file.inputStream()
        return input.use {
            config.serializer.deserialize(it.reader().buffered())
        }
    }

    @Test
    fun `register posts subscription and keeps record with delivered marker on success`() {
        val http = mockHttp()
        val (sut, config, storagePrefix) = getSut(http)

        sut.register("fcm-token", "firebase-project", "android")
        flush()

        val request = http.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/push_subscriptions/", request.path)

        val file = pendingFile(storagePrefix!!)
        assertTrue(file.exists())
        // Decision 5: the record is kept with the distinct id it was delivered for.
        assertEquals("distinct-1", readRecord(config, file)?.deliveredForDistinctId)
    }

    @Test
    fun `register defers when network is disconnected and keeps pending file`() {
        val http = mockHttp()
        val offline =
            object : PostHogNetworkStatus {
                override fun isConnected() = false
            }
        val (sut, _, storagePrefix) = getSut(http, networkStatus = offline)

        sut.register("fcm-token", "firebase-project", "android")
        flush()

        assertEquals(0, http.requestCount)
        assertTrue(pendingFile(storagePrefix!!).exists())
    }

    @Test
    fun `register resumes via the offline poll once connectivity returns`() {
        val http = mockHttp()
        var connected = false
        val network =
            object : PostHogNetworkStatus {
                override fun isConnected() = connected
            }
        val (sut, config, storagePrefix) = getSut(http, networkStatus = network)
        sut.retryDelayMillisPerSecond = 1L

        sut.register("fcm-token", "firebase-project", "android")
        flush()
        assertEquals(0, http.requestCount)

        connected = true

        // The offline deferral scheduled a poll without burning a retry attempt.
        assertNotNull(http.takeRequest(2, TimeUnit.SECONDS))
        flush() // let the attempt finish writing the delivered marker
        assertEquals(1, http.requestCount)
        assertEquals("distinct-1", readRecord(config, pendingFile(storagePrefix!!))?.deliveredForDistinctId)
    }

    @Test
    fun `register defers when distinctId is blank`() {
        val http = mockHttp()
        distinctId = "  "
        val (sut, _, storagePrefix) = getSut(http)

        sut.register("fcm-token", "firebase-project", "android")
        flush()

        assertEquals(0, http.requestCount)
        assertTrue(pendingFile(storagePrefix!!).exists())
    }

    @Test
    fun `register keeps file on non-retryable 400 without further in-session retries`() {
        val http = mockHttp(total = 5, response = MockResponse().setResponseCode(400).setBody("bad"))
        val (sut, config, storagePrefix) = getSut(http)
        sut.retryDelayMillisPerSecond = 1L

        sut.register("fcm-token", "firebase-project", "android")
        flush()

        // Vector 5: 400 -> no in-session retry, record kept (no delivered marker).
        assertNotNull(http.takeRequest(2, TimeUnit.SECONDS)) // the single 400
        assertNull(http.takeRequest(500, TimeUnit.MILLISECONDS)) // no retry scheduled
        assertEquals(1, http.requestCount)
        val file = pendingFile(storagePrefix!!)
        assertTrue(file.exists())
        assertNull(readRecord(config, file)?.deliveredForDistinctId)
    }

    @Test
    fun `retryPending does not re-send after a non-retryable failure halts the session`() {
        val http = mockHttp(total = 5, response = MockResponse().setResponseCode(400).setBody("bad"))
        val (sut, _, storagePrefix) = getSut(http)
        sut.retryDelayMillisPerSecond = 1L

        sut.register("fcm-token", "firebase-project", "android")
        assertNotNull(http.takeRequest(2, TimeUnit.SECONDS)) // the single 400
        assertEquals(1, http.requestCount)
        assertTrue(pendingFile(storagePrefix!!).exists())

        // flush() fires retryPending() on every app background; a 400-halted record must stay put and
        // not re-POST the doomed request each cycle.
        repeat(3) {
            sut.retryPending()
            flush()
        }

        assertNull(http.takeRequest(500, TimeUnit.MILLISECONDS))
        assertEquals(1, http.requestCount)
    }

    @Test
    fun `register retries on 500 then succeeds without duplicating`() {
        val http = MockWebServer()
        http.start()
        http.enqueue(MockResponse().setResponseCode(500))
        http.enqueue(MockResponse().setBody(""))

        val (sut, config, storagePrefix) = getSut(http)
        sut.retryDelayMillisPerSecond = 1L

        sut.register("fcm-token", "firebase-project", "android")

        assertNotNull(http.takeRequest(2, TimeUnit.SECONDS)) // initial 500
        assertNotNull(http.takeRequest(2, TimeUnit.SECONDS)) // retry ~5ms -> 200
        assertNull(http.takeRequest(500, TimeUnit.MILLISECONDS)) // no duplicate
        assertEquals(2, http.requestCount)

        val file = pendingFile(storagePrefix!!)
        assertTrue(file.exists())
        assertEquals("distinct-1", readRecord(config, file)?.deliveredForDistinctId)
        http.shutdown()
    }

    @Test
    fun `register gives up after maxRetries then halts in-session but a fresh instance retries once`() {
        val http = MockWebServer()
        http.start()
        repeat(3) { http.enqueue(MockResponse().setResponseCode(500)) } // initial + 2 retries
        http.enqueue(MockResponse().setBody("")) // the single retry a relaunch is allowed

        val storagePrefix = tmpDir.newFolder().absolutePath
        val (sut, _, _) = getSut(http, storagePrefix = storagePrefix, maxRetries = 2)
        sut.retryDelayMillisPerSecond = 1L

        sut.register("fcm-token", "firebase-project", "android")

        // Vector 4: 500 -> retry, 500 -> retry, then give up after maxRetries.
        assertNotNull(http.takeRequest(2, TimeUnit.SECONDS)) // initial
        assertNotNull(http.takeRequest(2, TimeUnit.SECONDS)) // retry 1
        assertNotNull(http.takeRequest(2, TimeUnit.SECONDS)) // retry 2
        assertNull(http.takeRequest(1, TimeUnit.SECONDS)) // gave up, record kept
        assertTrue(pendingFile(storagePrefix).exists())

        // Halted for the rest of this session: flush()-driven retryPending() must not re-hit the endpoint.
        sut.retryPending()
        flush()
        assertNull(http.takeRequest(500, TimeUnit.MILLISECONDS))
        assertEquals(3, http.requestCount)

        // Relaunch: a fresh manager over the same storage clears the in-memory halt and retries exactly once.
        val (relaunched, _, _) = getSut(http, storagePrefix = storagePrefix, maxRetries = 2)
        relaunched.retryPending()
        assertNotNull(http.takeRequest(2, TimeUnit.SECONDS))
        assertEquals(4, http.requestCount)
        http.shutdown()
    }

    @Test
    fun `nextBackoffSeconds follows 5-10-20-30 with cap and honours Retry-After`() {
        val http = mockHttp()
        val (sut, _, _) = getSut(http)

        assertEquals(5, sut.nextBackoffSeconds(1, null))
        assertEquals(10, sut.nextBackoffSeconds(2, null))
        assertEquals(20, sut.nextBackoffSeconds(3, null))
        assertEquals(30, sut.nextBackoffSeconds(4, null))
        assertEquals(30, sut.nextBackoffSeconds(5, null))

        // Retry-After wins when present and positive; 0/absent falls back to the formula.
        assertEquals(7, sut.nextBackoffSeconds(1, 7))
        assertEquals(5, sut.nextBackoffSeconds(1, 0))
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
    fun `retryPending is a no-op when already delivered for current distinct id`() {
        val http = mockHttp(total = 2)
        val (sut, _, _) = getSut(http)

        sut.register("fcm-token", "firebase-project", "android")
        flush()
        assertEquals(1, http.requestCount)

        sut.retryPending()
        flush()

        assertEquals(1, http.requestCount)
    }

    @Test
    fun `resendIfDistinctIdChanged re-registers when the distinct id changes`() {
        val http = mockHttp(total = 2, response = MockResponse().setBody(""))
        val (sut, config, storagePrefix) = getSut(http)

        sut.register("fcm-token", "firebase-project", "android")
        flush()
        assertEquals("distinct-1", parsedDistinctId(http.takeRequest()))

        distinctId = "distinct-2"
        sut.resendIfDistinctIdChanged()
        flush()

        assertEquals(2, http.requestCount)
        assertEquals("distinct-2", parsedDistinctId(http.takeRequest()))
        assertEquals("distinct-2", readRecord(config, pendingFile(storagePrefix!!))?.deliveredForDistinctId)
    }

    @Test
    fun `resendIfDistinctIdChanged is a no-op when the distinct id is unchanged`() {
        val http = mockHttp(total = 2)
        val (sut, _, _) = getSut(http)

        sut.register("fcm-token", "firebase-project", "android")
        flush()

        sut.resendIfDistinctIdChanged()
        flush()

        assertEquals(1, http.requestCount)
    }

    @Test
    fun `unregister sends one DELETE and does not retry on failure`() {
        val http = mockHttp(total = 5, response = MockResponse().setResponseCode(500))
        val (sut, _, _) = getSut(http)
        sut.retryDelayMillisPerSecond = 1L

        sut.unregister("distinct-1", "fcm-token", "firebase-project", "android")
        flush()

        val request = http.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/push_subscriptions/", request.path)
        // Best-effort: exactly one request, no retry even on 500.
        assertNull(http.takeRequest(500, TimeUnit.MILLISECONDS))
        assertEquals(1, http.requestCount)
    }

    @Test
    fun `handleReset unregisters the old identity then re-registers under the new anonymous id`() {
        val http = mockHttp(total = 3, response = MockResponse().setBody(""))
        val (sut, config, storagePrefix) = getSut(http)

        distinctId = "user-A"
        sut.register("fcm-token", "firebase-project", "android")
        flush()
        assertEquals("POST", http.takeRequest().method)

        // Log out: a new anonymous id is now current.
        distinctId = "anon-2"
        sut.handleReset("user-A")
        flush()

        // Vector 8: DELETE for the old identity, then a POST re-register under the new anon id.
        val del = http.takeRequest()
        assertEquals("DELETE", del.method)
        assertEquals("user-A", parsedDistinctId(del))

        val post = http.takeRequest()
        assertEquals("POST", post.method)
        assertEquals("anon-2", parsedDistinctId(post))

        assertEquals(3, http.requestCount)
        assertEquals("anon-2", readRecord(config, pendingFile(storagePrefix!!))?.deliveredForDistinctId)
    }

    @Test
    fun `handleReset does not unregister when the identity is unchanged`() {
        val http = mockHttp(total = 3, response = MockResponse().setBody(""))
        val (sut, _, storagePrefix) = getSut(http)

        sut.register("fcm-token", "firebase-project", "android")
        flush()
        assertEquals("POST", http.takeRequest().method)

        // reuseAnonymousId keeps the same id across reset(): old == new. A DELETE here would unset
        // the id we stay on, and the re-register dedup guard would then skip the POST.
        sut.handleReset("distinct-1")
        flush()

        assertNull(http.takeRequest(500, TimeUnit.MILLISECONDS))
        assertEquals(1, http.requestCount)
        assertTrue(pendingFile(storagePrefix!!).exists())
    }

    @Test
    fun `unregisterCurrent deletes for the current id and clears the pending record`() {
        val http = mockHttp(total = 2, response = MockResponse().setBody(""))
        val (sut, _, storagePrefix) = getSut(http)

        sut.register("fcm-token", "firebase-project", "android")
        flush()
        assertEquals("POST", http.takeRequest().method)
        assertTrue(pendingFile(storagePrefix!!).exists())

        sut.unregisterCurrent()
        flush()

        val del = http.takeRequest()
        assertEquals("DELETE", del.method)
        assertEquals("distinct-1", parsedDistinctId(del))
        // Record forgotten so a later retryPending won't re-send it.
        assertFalse(pendingFile(storagePrefix).exists())
    }

    @Test
    fun `unregister does not send after optOut`() {
        val http = mockHttp()
        val (sut, config, _) = getSut(http)
        config.optOut = true

        sut.unregister("distinct-1", "fcm-token", "firebase-project", "android")
        flush()

        assertEquals(0, http.requestCount)
    }

    @Test
    fun `register overwrites the pending record latest-wins`() {
        val http = mockHttp(total = 2, response = MockResponse().setBody(""))
        val (sut, _, storagePrefix) = getSut(http)

        sut.register("token-1", "firebase-project", "android")
        flush()
        sut.register("token-2", "firebase-project", "android")
        flush()

        assertEquals(2, http.requestCount)
        assertTrue(http.takeRequest().body.unGzip().contains("token-1"))
        assertTrue(http.takeRequest().body.unGzip().contains("token-2"))
        assertTrue(pendingFile(storagePrefix!!).exists())
    }

    @Test
    fun `register writes an encrypted record that roundtrips through the serializer`() {
        val encryption = XorEncryption()
        val http = mockHttp(response = MockResponse().setResponseCode(503))
        val (sut, config, storagePrefix) = getSut(http, maxRetries = 0, encryption = encryption)

        sut.register("fcm-token", "firebase-project", "android")
        flush()

        val file = pendingFile(storagePrefix!!)
        assertTrue(file.exists())
        // Raw bytes are not the plaintext token; decryption yields the record.
        assertFalse(file.readBytes().decodeToString().contains("fcm-token"))
        val record = readRecord(config, file)
        assertEquals("fcm-token", record?.deviceToken)
        assertEquals("firebase-project", record?.appId)
        assertEquals("android", record?.platform)
        assertNull(record?.deliveredForDistinctId)
    }

    @Test
    fun `register with null storagePrefix still attempts the request`() {
        val http = mockHttp()
        val (sut, _, _) = getSut(http, storagePrefix = null)

        sut.register("fcm-token", "firebase-project", "android")
        flush()

        assertEquals(1, http.requestCount)
    }

    @Test
    fun `retryPending hydrates a persisted record from disk on a fresh instance`() {
        val http = MockWebServer()
        http.start()
        http.enqueue(MockResponse().setResponseCode(503)) // first launch: fails, keeps file
        http.enqueue(MockResponse().setBody("")) // next launch: succeeds

        val storagePrefix = tmpDir.newFolder().absolutePath
        val (first, _, _) = getSut(http, storagePrefix = storagePrefix, maxRetries = 0)
        first.register("fcm-token", "firebase-project", "android")
        flush()
        assertTrue(pendingFile(storagePrefix).exists())

        // Next launch: a brand-new manager with empty in-memory state must hydrate from disk.
        val (second, config, _) = getSut(http, storagePrefix = storagePrefix)
        second.retryPending()
        flush()

        assertEquals(2, http.requestCount)
        assertEquals("distinct-1", readRecord(config, pendingFile(storagePrefix))?.deliveredForDistinctId)
        http.shutdown()
    }

    @Test
    fun `retryPending deletes a corrupt pending file`() {
        val http = mockHttp()
        val (sut, _, storagePrefix) = getSut(http)

        val file = pendingFile(storagePrefix!!)
        file.parentFile.mkdirs()
        file.writeText("{not valid json")

        sut.retryPending()
        flush()

        assertEquals(0, http.requestCount)
        assertFalse(file.exists())
    }

    @Test
    fun `retryPending does not send after optOut`() {
        val http = mockHttp()
        var connected = false
        val network =
            object : PostHogNetworkStatus {
                override fun isConnected() = connected
            }
        val (sut, config, storagePrefix) = getSut(http, networkStatus = network)

        // Persist an undelivered record (register defers while offline).
        sut.register("fcm-token", "firebase-project", "android")
        flush()
        assertEquals(0, http.requestCount)
        assertTrue(pendingFile(storagePrefix!!).exists())

        connected = true
        config.optOut = true
        sut.retryPending()
        flush()

        assertNull(http.takeRequest(500, TimeUnit.MILLISECONDS))
        assertEquals(0, http.requestCount)
    }

    @Test
    fun `resendIfDistinctIdChanged does not send after optOut`() {
        val http = mockHttp()
        val (sut, config, _) = getSut(http)

        sut.register("fcm-token", "firebase-project", "android")
        flush()
        assertNotNull(http.takeRequest(2, TimeUnit.SECONDS)) // delivered for distinct-1
        assertEquals(1, http.requestCount)

        config.optOut = true
        distinctId = "distinct-2" // identify as someone new
        sut.resendIfDistinctIdChanged()
        flush()

        assertNull(http.takeRequest(500, TimeUnit.MILLISECONDS))
        assertEquals(1, http.requestCount)
    }

    @Test
    fun `retryPending honours an active backoff window instead of re-hitting immediately`() {
        val http = MockWebServer()
        http.start()
        http.enqueue(MockResponse().setResponseCode(500)) // opens a 5s backoff window
        http.enqueue(MockResponse().setBody("")) // the re-hit that must NOT happen during the window

        // Real 1000ms/sec so the 5s window stays open for the whole test.
        val (sut, _, _) = getSut(http)

        sut.register("fcm-token", "firebase-project", "android")
        assertNotNull(http.takeRequest(2, TimeUnit.SECONDS)) // the 500
        assertEquals(1, http.requestCount)

        // A background flush lands mid-backoff: it must let the scheduled retry fire, not re-POST now.
        sut.retryPending()
        flush()

        assertNull(http.takeRequest(500, TimeUnit.MILLISECONDS))
        assertEquals(1, http.requestCount)
        http.shutdown()
    }

    @Test
    fun `register skips re-sending a token already delivered for the current distinct id`() {
        val http = mockHttp()
        val (sut, _, _) = getSut(http)

        sut.register("fcm-token", "firebase-project", "android")
        flush()
        assertNotNull(http.takeRequest(2, TimeUnit.SECONDS))
        assertEquals(1, http.requestCount)

        // Cold-start auto-register forwards the same cached token again for the same user.
        sut.register("fcm-token", "firebase-project", "android")
        flush()

        assertNull(http.takeRequest(500, TimeUnit.MILLISECONDS))
        assertEquals(1, http.requestCount)
    }

    private fun parsedDistinctId(request: okhttp3.mockwebserver.RecordedRequest): String? {
        val serializer = PostHogSerializer(PostHogConfig(API_KEY))
        val parsed = serializer.deserialize<Map<String, Any>>(request.body.unGzip().reader())
        return parsed["distinct_id"] as? String
    }

    private class XorEncryption : PostHogEncryption {
        private val key = 0x5A.toByte()

        override fun encrypt(outputStream: OutputStream): OutputStream =
            object : OutputStream() {
                override fun write(b: Int) = outputStream.write(b xor key.toInt())

                override fun flush() = outputStream.flush()

                override fun close() = outputStream.close()
            }

        override fun decrypt(inputStream: InputStream): InputStream =
            object : InputStream() {
                override fun read(): Int {
                    val next = inputStream.read()
                    return if (next == -1) -1 else next xor key.toInt()
                }

                override fun close() = inputStream.close()
            }
    }
}

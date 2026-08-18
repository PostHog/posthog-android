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
import java.util.concurrent.CountDownLatch
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
        pushAppIds: List<String>? = null,
    ): Triple<PostHogPushSubscriptionManager, PostHogConfig, String?> {
        val config =
            PostHogConfig(API_KEY, host = http.url("/").toString()).apply {
                this.storagePrefix = storagePrefix
                this.networkStatus = networkStatus
                this.maxRetries = maxRetries
                this.encryption = encryption
            }
        val api = PostHogApi(config)
        val manager = PostHogPushSubscriptionManager(config, api, executor, { distinctId }, { pushAppIds })
        return Triple(manager, config, storagePrefix)
    }

    private fun pendingFile(storagePrefix: String): File = File(File(File(storagePrefix, "push"), API_KEY), "push_subscription.pending")

    private fun pendingUnregisterFile(storagePrefix: String): File =
        File(File(File(storagePrefix, "push"), API_KEY), "push_subscription.unregister.pending")

    private fun flush() {
        executor.submit {}.get()
    }

    private fun readRecord(
        config: PostHogConfig,
        file: File,
    ): PostHogPushSubscriptionManager.PendingRecord? {
        if (!file.exists()) return null
        val input = config.encryption?.decrypt(file.inputStream()) ?: file.inputStream()
        return input.use {
            config.serializer.deserialize(it.reader().buffered())
        }
    }

    private fun readUnregister(
        config: PostHogConfig,
        file: File,
    ): PostHogPushSubscriptionManager.PendingUnregister? {
        if (!file.exists()) return null
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
    fun `register resumes on retryPending once connectivity returns, without an offline poll`() {
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

        // Passive model (parity with iOS): the offline deferral schedules no timer, so nothing is
        // sent even after connectivity returns until a resume trigger fires.
        connected = true
        assertNull(http.takeRequest(500, TimeUnit.MILLISECONDS))

        // flush()/relaunch drives recovery via retryPending().
        sut.retryPending()
        assertNotNull(http.takeRequest(2, TimeUnit.SECONDS))
        flush()
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
        // No self-firing timer: nothing retries until an external trigger lands after the window.
        assertNull(http.takeRequest(500, TimeUnit.MILLISECONDS))
        Thread.sleep(50) // backoff window is ~5ms at the test's 1ms/s scale
        sut.retryPending()
        assertNotNull(http.takeRequest(2, TimeUnit.SECONDS)) // flush-driven retry -> 200
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

        // Vector 4: the retry ladder persists across flush-driven triggers, so repeated failures
        // exhaust maxRetries: 500, then flush retry 500, flush retry 500, halt.
        assertNotNull(http.takeRequest(2, TimeUnit.SECONDS)) // initial
        repeat(2) {
            Thread.sleep(50) // let the ms-scaled backoff window elapse
            sut.retryPending()
            assertNotNull(http.takeRequest(2, TimeUnit.SECONDS))
        }
        assertTrue(pendingFile(storagePrefix).exists())

        // Halted for the rest of this session: flush()-driven retryPending() must not re-hit the endpoint.
        Thread.sleep(50)
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

    // Regression test for posthog-android#675: opt-out mid-unregister must still send the DELETE.
    @Test
    fun `opt-out during an in-flight unregister strands the DELETE (posthog-android#675)`() {
        val http = mockHttp(total = 2) // register POST, then the unregister DELETE
        val (sut, config, _) = getSut(http)

        // 1) Register and deliver a token.
        sut.register("fcm-token", "firebase-project", "android")
        flush()
        assertEquals("POST", http.takeRequest().method)

        // 2) The wrapper's opt-out flow: unregister, then opt out. Block the single executor thread so
        //    opt-out flips config.optOut before the queued unregister task runs (the reported race).
        val gate = CountDownLatch(1)
        executor.execute { gate.await() }
        sut.unregisterCurrent() // queues performUnregister behind the gate
        config.optOut = true // opt-out lands first
        gate.countDown() // release: the unregister now runs while opted out
        flush()

        // A DELETE removes data, so opt-out must not block it. Fails today: the
        // `if (closed || config.optOut) return` guard strands the DELETE and the server-side
        // subscription stays active for the whole opted-out period.
        val delete = http.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(delete, "posthog-android#675: opt-out stranded the unregister DELETE; subscription stays active")
        assertEquals("DELETE", delete.method)
    }

    @Test
    fun `a mint completing after optOut is not cached and opt-in re-mints`() {
        val http = mockHttp(total = 2, response = MockResponse().setBody(""))
        val (sut, config, _) = getSut(http)
        var pending: ((String?) -> Unit)? = null
        val minted = java.util.concurrent.atomic.AtomicInteger(0)
        config.pushIdentityProvider = { _, _, completion ->
            minted.incrementAndGet()
            pending = completion
        }

        sut.register("fcm-token", "firebase-project", "android")
        flush()
        config.optOut = true
        sut.onOptOut()
        pending!!.invoke("jwt-stale")
        flush()

        config.optOut = false
        sut.register("fcm-token", "firebase-project", "android")
        flush()
        pending!!.invoke("jwt-fresh")
        flush()

        assertEquals(2, minted.get())
        assertTrue(http.takeRequest().body.unGzip().contains("\"identity_token\":\"jwt-fresh\""))
    }

    @Test
    fun `a registration arriving mid-mint is replayed and the stale send bails`() {
        // A + B: a new token registers while the first send's identity token is still minting. The
        // stale first send must bail (its record was superseded) and the newer registration must be
        // replayed, so exactly one POST goes out — carrying the second token, not the first. The replay
        // reuses the just-cached token (same distinctId/appId), so the provider is only invoked once.
        val http = mockHttp(total = 2, response = MockResponse().setBody(""))
        val (sut, config, _) = getSut(http)
        val mints = java.util.concurrent.LinkedBlockingQueue<(String?) -> Unit>()
        config.pushIdentityProvider = { _, _, completion -> mints.add(completion) }

        sut.register("fcm-token-1", "firebase-project", "android")
        flush()
        // First mint is still outstanding (isSending held); a newer token registers mid-mint.
        sut.register("fcm-token-2", "firebase-project", "android")
        flush()

        // First mint completes: its record is now stale (fcm-token-2 superseded it), so the send bails
        // and replays the pending registration, which sends the one real POST with the cached token.
        mints.take().invoke("jwt-abc")
        flush()

        val post = http.takeRequest()
        assertEquals("POST", post.method)
        val body = post.body.unGzip()
        assertTrue(body.contains("\"device_token\":\"fcm-token-2\""))
        assertTrue(body.contains("\"identity_token\":\"jwt-abc\""))
        assertNull(http.takeRequest(500, TimeUnit.MILLISECONDS)) // no stale POST for fcm-token-1
        assertEquals(1, http.requestCount)
        assertEquals(0, mints.size) // provider invoked once; the replay reused the cache
    }

    @Test
    fun `a provider that never completes falls back to a token-less send instead of wedging`() {
        // D: an outstanding mint holds isSending across the whole process. The watchdog must fire a
        // token-less send so sending recovers instead of being wedged forever.
        val http = mockHttp(total = 2, response = MockResponse().setBody(""))
        val (sut, config, _) = getSut(http)
        sut.identityTokenMintTimeoutMillis = 50
        config.pushIdentityProvider = { _, _, _ -> } // never calls completion

        sut.register("fcm-token-1", "firebase-project", "android")
        val post = http.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(post)
        assertEquals("POST", post!!.method)
        assertFalse(post.body.unGzip().contains("identity_token"))

        // isSending was released by the fallback, so a later registration is not wedged.
        sut.register("fcm-token-2", "firebase-project", "android")
        val post2 = http.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(post2)
        assertTrue(post2!!.body.unGzip().contains("\"device_token\":\"fcm-token-2\""))
    }

    @Test
    fun `clearing the provider mid-session stops attaching the cached token`() {
        // F: the provider is checked before the cache, so removing pushIdentityProvider mid-session
        // sends token-less immediately instead of riding the previously cached credential.
        val http = mockHttp(total = 2, response = MockResponse().setBody(""))
        val (sut, config, _) = getSut(http)
        config.pushIdentityProvider = { _, _, completion -> completion("jwt-cached") }

        sut.register("fcm-token-1", "firebase-project", "android")
        flush()
        assertTrue(http.takeRequest().body.unGzip().contains("\"identity_token\":\"jwt-cached\""))

        // App removes the provider; a subsequent send for the same distinctId/appId must not reuse the cache.
        config.pushIdentityProvider = null
        sut.register("fcm-token-2", "firebase-project", "android")
        flush()

        val body = http.takeRequest().body.unGzip()
        assertTrue(body.contains("\"device_token\":\"fcm-token-2\""))
        assertFalse(body.contains("identity_token"))
    }

    @Test
    fun `unregister DELETE refreshes the identity token once on a 401`() {
        // Durable parity with the send path: a 401 re-mints and retries the DELETE exactly once.
        val http = mockHttp(total = 3, response = MockResponse().setResponseCode(401))
        val (sut, config, storagePrefix) = getSut(http)
        val minted = java.util.concurrent.atomic.AtomicInteger(0)
        config.pushIdentityProvider = { _, _, completion -> completion("jwt-${minted.incrementAndGet()}") }

        sut.unregister("distinct-1", "fcm-token", "firebase-project", "android")
        flush()

        assertEquals("DELETE", http.takeRequest().method)
        // One fresh-token retry after the 401, then terminal — no infinite loop.
        assertEquals("DELETE", http.takeRequest(2, TimeUnit.SECONDS)!!.method)
        assertNull(http.takeRequest(500, TimeUnit.MILLISECONDS))
        assertEquals(2, http.requestCount)
        assertEquals(2, minted.get())
        // Post-retry 401 stops for this session but keeps the intent: dropping it here would
        // permanently strand the device on the logged-out user. retryPending() re-attempts later.
        assertNotNull(readUnregister(config, pendingUnregisterFile(storagePrefix!!)))
    }

    @Test
    fun `unregister persists the intent and replays it on retryPending after a transient failure`() {
        val http = mockHttp(total = 1, response = MockResponse().setResponseCode(503))
        val (sut, config, storagePrefix) = getSut(http)
        sut.retryDelayMillisPerSecond = 1L

        sut.unregister("distinct-1", "fcm-token", "firebase-project", "android")
        flush()

        // 503 is retryable: the DELETE fired but the intent is kept for a later drain.
        assertEquals("DELETE", http.takeRequest().method)
        assertNotNull(readUnregister(config, pendingUnregisterFile(storagePrefix!!)))

        // A later flush()/relaunch replays it; server now accepts, intent cleared.
        http.enqueue(MockResponse().setBody(""))
        sut.retryPending()
        flush()
        assertEquals("DELETE", http.takeRequest(2, TimeUnit.SECONDS)!!.method)
        assertNull(readUnregister(config, pendingUnregisterFile(storagePrefix!!)))
    }

    @Test
    fun `unregister while offline defers the DELETE and replays it on retryPending`() {
        val http = mockHttp()
        var connected = false
        val network =
            object : PostHogNetworkStatus {
                override fun isConnected() = connected
            }
        val (sut, config, storagePrefix) = getSut(http, networkStatus = network)

        sut.unregister("distinct-1", "fcm-token", "firebase-project", "android")
        flush()

        // Offline: nothing sent, but the intent is persisted so the logout isn't dropped.
        assertEquals(0, http.requestCount)
        assertNotNull(readUnregister(config, pendingUnregisterFile(storagePrefix!!)))

        connected = true
        sut.retryPending()
        flush()
        assertEquals("DELETE", http.takeRequest(2, TimeUnit.SECONDS)!!.method)
        assertNull(readUnregister(config, pendingUnregisterFile(storagePrefix!!)))
    }

    @Test
    fun `retryPending drops a pending unregister for the current identity when a registration is queued`() {
        val http = mockHttp()
        var connected = false
        val network =
            object : PostHogNetworkStatus {
                override fun isConnected() = connected
            }
        val (sut, config, storagePrefix) = getSut(http, networkStatus = network)

        // Log out of distinct-1 while offline, then re-register for the same identity.
        sut.unregister("distinct-1", "fcm-token", "firebase-project", "android")
        sut.register("fcm-token", "firebase-project", "android")
        flush()
        assertEquals(0, http.requestCount)
        assertNotNull(readUnregister(config, pendingUnregisterFile(storagePrefix!!)))

        connected = true
        sut.retryPending()
        flush()

        // Register supersedes the queued DELETE: only the POST goes out, the intent is dropped.
        val request = http.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals(1, http.requestCount)
        assertNull(readUnregister(config, pendingUnregisterFile(storagePrefix)))
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
    fun `a retry that fires during an unregister does not re-subscribe the cleared token`() {
        // Race: unregisterCurrent()'s DELETE holds the single-thread executor while the pending retry timer
        // fires and queues behind it; that attempt must see the cleared record and bail, not re-POST the token.
        val http = MockWebServer()
        http.start()
        http.enqueue(MockResponse().setResponseCode(500)) // initial register fails -> schedules a retry
        http.enqueue(MockResponse().setBody("").setHeadersDelay(300, TimeUnit.MILLISECONDS)) // DELETE holds the executor
        http.enqueue(MockResponse().setBody("")) // only consumed if the bug re-POSTs

        val (sut, _, storagePrefix) = getSut(http)
        sut.retryDelayMillisPerSecond = 1L // 5s backoff -> ~5ms, fires well inside the 300ms DELETE

        sut.register("fcm-token", "firebase-project", "android")
        sut.unregisterCurrent()

        assertEquals("POST", http.takeRequest(2, TimeUnit.SECONDS)?.method)
        assertEquals("DELETE", http.takeRequest(2, TimeUnit.SECONDS)?.method)
        assertNull(http.takeRequest(1, TimeUnit.SECONDS)) // no re-POST: the retry saw a cleared record and bailed
        assertEquals(2, http.requestCount)
        assertFalse(pendingFile(storagePrefix!!).exists()) // record stays cleared, not rewritten by a re-send
        http.shutdown()
    }

    @Test
    fun `unregister still sends the DELETE while opted out`() {
        // Opt-out blocks registration and sends, but a DELETE is data removal, so it must still go out
        // (posthog-android#675) — otherwise the server-side subscription outlives the opt-out.
        val http = mockHttp()
        val (sut, config, _) = getSut(http)
        config.optOut = true

        sut.unregister("distinct-1", "fcm-token", "firebase-project", "android")
        flush()

        val request = http.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(request)
        assertEquals("DELETE", request.method)
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

    @Test
    fun `register and unregisterCurrent attach the provider token to POST and DELETE bodies`() {
        // Vector 9: provider set completing "jwt-abc" -> both legs carry identity_token alongside the 5 fields.
        val http = mockHttp(total = 2, response = MockResponse().setBody(""))
        val (sut, config, _) = getSut(http)
        config.pushIdentityProvider = { _, _, completion -> completion("jwt-abc") }

        sut.register("fcm-token", "firebase-project", "android")
        flush()

        val post = http.takeRequest()
        assertEquals("POST", post.method)
        val postBody = post.body.unGzip()
        assertTrue(postBody.contains("\"identity_token\":\"jwt-abc\""))
        assertTrue(postBody.contains("\"api_key\""))
        assertTrue(postBody.contains("\"distinct_id\""))
        assertTrue(postBody.contains("\"device_token\""))
        assertTrue(postBody.contains("\"platform\""))
        assertTrue(postBody.contains("\"app_id\""))

        sut.unregisterCurrent()
        flush()

        val del = http.takeRequest()
        assertEquals("DELETE", del.method)
        assertTrue(del.body.unGzip().contains("\"identity_token\":\"jwt-abc\""))
    }

    @Test
    fun `register without a provider omits identity_token from the raw body`() {
        // Vector 10: no provider -> the serialized body has no identity_token key at all.
        val http = mockHttp()
        val (sut, _, _) = getSut(http)

        sut.register("fcm-token", "firebase-project", "android")
        flush()

        assertFalse(http.takeRequest().body.unGzip().contains("identity_token"))
    }

    @Test
    fun `register with a provider completing null sends token-less`() {
        // Vector 10: completion(null) -> key omitted, request still goes out and delivers.
        val http = mockHttp()
        val (sut, config, storagePrefix) = getSut(http)
        config.pushIdentityProvider = { _, _, completion -> completion(null) }

        sut.register("fcm-token", "firebase-project", "android")
        flush()
        flush() // the null completion re-enters via a queued executor task

        assertFalse(http.takeRequest(2, TimeUnit.SECONDS)!!.body.unGzip().contains("identity_token"))
        flush()
        assertEquals("distinct-1", readRecord(config, pendingFile(storagePrefix!!))?.deliveredForDistinctId)
    }

    @Test
    fun `handleReset resolves the old id token for the DELETE and the anon id token for the re-POST`() {
        // Vector 11: each leg carries a token for the distinct id it sends; cached tokens are reused
        // per (distinctId, appId), so only ids never minted before invoke the provider.
        val http = mockHttp(total = 4, response = MockResponse().setBody(""))
        val (sut, config, _) = getSut(http)
        val invocations = mutableListOf<String>()
        config.pushIdentityProvider = { id, _, completion ->
            synchronized(invocations) { invocations.add(id) }
            completion("tok-$id")
        }

        distinctId = "user-A"
        sut.register("fcm-token", "firebase-project", "android")
        flush()
        assertTrue(http.takeRequest(2, TimeUnit.SECONDS)!!.body.unGzip().contains("\"identity_token\":\"tok-user-A\""))

        distinctId = "user-B"
        sut.resendIfDistinctIdChanged()
        flush()
        assertTrue(http.takeRequest(2, TimeUnit.SECONDS)!!.body.unGzip().contains("\"identity_token\":\"tok-user-B\""))

        distinctId = "anon-2"
        sut.handleReset("user-B")

        val del = http.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("DELETE", del.method)
        val delBody = del.body.unGzip()
        assertTrue(delBody.contains("\"distinct_id\":\"user-B\""))
        assertTrue(delBody.contains("\"identity_token\":\"tok-user-B\""))

        val post = http.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("POST", post.method)
        val postBody = post.body.unGzip()
        assertTrue(postBody.contains("\"distinct_id\":\"anon-2\""))
        assertTrue(postBody.contains("\"identity_token\":\"tok-anon-2\""))

        flush()
        // The DELETE leg reused user-B's cached token; only the three distinct ids minted, once each.
        assertEquals(listOf("user-A", "user-B", "anon-2"), synchronized(invocations) { invocations.toList() })
    }

    @Test
    fun `a 500 retry reuses the cached token without re-minting`() {
        // Vector 12: 500 then 200 -> provider invoked once, both attempts carry the same token.
        val http = MockWebServer()
        http.start()
        http.enqueue(MockResponse().setResponseCode(500))
        http.enqueue(MockResponse().setBody(""))

        val (sut, config, _) = getSut(http)
        sut.retryDelayMillisPerSecond = 1L
        val minted = java.util.concurrent.atomic.AtomicInteger(0)
        config.pushIdentityProvider = { _, _, completion -> completion("jwt-${minted.incrementAndGet()}") }

        sut.register("fcm-token", "firebase-project", "android")

        assertTrue(http.takeRequest(2, TimeUnit.SECONDS)!!.body.unGzip().contains("\"identity_token\":\"jwt-1\""))
        Thread.sleep(50) // let the ms-scaled backoff window elapse
        sut.retryPending()
        assertTrue(http.takeRequest(2, TimeUnit.SECONDS)!!.body.unGzip().contains("\"identity_token\":\"jwt-1\""))
        assertEquals(2, http.requestCount)
        assertEquals(1, minted.get())
        http.shutdown()
    }

    @Test
    fun `a 401 re-mints once and retries with the fresh token`() {
        // Vector 13: 401 then 200 -> provider invoked a second time, retry carries the fresh token.
        val http = MockWebServer()
        http.start()
        http.enqueue(MockResponse().setResponseCode(401))
        http.enqueue(MockResponse().setBody(""))

        val (sut, config, storagePrefix) = getSut(http)
        val minted = java.util.concurrent.atomic.AtomicInteger(0)
        config.pushIdentityProvider = { _, _, completion -> completion("jwt-${minted.incrementAndGet()}") }

        sut.register("fcm-token", "firebase-project", "android")

        assertTrue(http.takeRequest(2, TimeUnit.SECONDS)!!.body.unGzip().contains("\"identity_token\":\"jwt-1\""))
        assertTrue(http.takeRequest(2, TimeUnit.SECONDS)!!.body.unGzip().contains("\"identity_token\":\"jwt-2\""))
        assertNull(http.takeRequest(500, TimeUnit.MILLISECONDS))
        assertEquals(2, minted.get())
        flush()
        assertEquals("distinct-1", readRecord(config, pendingFile(storagePrefix!!))?.deliveredForDistinctId)
        http.shutdown()
    }

    @Test
    fun `a second 401 is terminal after the single refresh`() {
        // Vector 13: 401 then 401 -> exactly two provider invocations and two requests, then halt.
        val http = MockWebServer()
        http.start()
        http.enqueue(MockResponse().setResponseCode(401))
        http.enqueue(MockResponse().setResponseCode(401))
        http.enqueue(MockResponse().setBody("")) // only consumed if the halt fails

        val (sut, config, storagePrefix) = getSut(http)
        val minted = java.util.concurrent.atomic.AtomicInteger(0)
        config.pushIdentityProvider = { _, _, completion -> completion("jwt-${minted.incrementAndGet()}") }

        sut.register("fcm-token", "firebase-project", "android")

        assertNotNull(http.takeRequest(2, TimeUnit.SECONDS))
        assertNotNull(http.takeRequest(2, TimeUnit.SECONDS))
        assertNull(http.takeRequest(500, TimeUnit.MILLISECONDS))
        assertEquals(2, http.requestCount)
        assertEquals(2, minted.get())

        // Halted for the session: record kept without a delivered marker, resume paths are no-ops.
        val file = pendingFile(storagePrefix!!)
        assertTrue(file.exists())
        assertNull(readRecord(config, file)?.deliveredForDistinctId)
        sut.retryPending()
        flush()
        assertNull(http.takeRequest(500, TimeUnit.MILLISECONDS))
        assertEquals(2, http.requestCount)
        http.shutdown()
    }

    @Test
    fun `a 401 without a provider halts immediately and logs the provider hint`() {
        // Vector 14: one request, no retry, record kept, log names pushIdentityProvider.
        val http = mockHttp(total = 5, response = MockResponse().setResponseCode(401))
        val (sut, config, storagePrefix) = getSut(http)
        val messages = mutableListOf<String>()
        config.logger =
            object : PostHogLogger {
                override fun log(message: String) {
                    synchronized(messages) { messages.add(message) }
                }

                override fun isEnabled(): Boolean = true
            }

        sut.register("fcm-token", "firebase-project", "android")
        flush()

        assertNotNull(http.takeRequest(2, TimeUnit.SECONDS))
        assertNull(http.takeRequest(500, TimeUnit.MILLISECONDS))
        assertEquals(1, http.requestCount)
        val file = pendingFile(storagePrefix!!)
        assertTrue(file.exists())
        assertNull(readRecord(config, file)?.deliveredForDistinctId)
        assertTrue(synchronized(messages) { messages.any { it.contains("pushIdentityProvider") } })
    }

    @Test
    fun `a throwing provider sends token-less`() {
        val http = mockHttp()
        val (sut, config, _) = getSut(http)
        config.pushIdentityProvider = { _, _, _ -> throw RuntimeException("mint failed") }

        sut.register("fcm-token", "firebase-project", "android")
        flush()

        val request = http.takeRequest(2, TimeUnit.SECONDS)!!
        assertFalse(request.body.unGzip().contains("identity_token"))
        assertEquals(1, http.requestCount)
    }

    @Test
    fun `a provider completing from another thread still attaches the token`() {
        val http = mockHttp()
        val (sut, config, _) = getSut(http)
        config.pushIdentityProvider = { _, _, completion ->
            Thread { completion("jwt-thread") }.start()
        }

        sut.register("fcm-token", "firebase-project", "android")

        assertTrue(http.takeRequest(2, TimeUnit.SECONDS)!!.body.unGzip().contains("\"identity_token\":\"jwt-thread\""))
    }

    @Test
    fun `only the first provider completion is honored`() {
        val http = mockHttp(total = 2)
        val (sut, config, _) = getSut(http)
        config.pushIdentityProvider = { _, _, completion ->
            completion("first")
            completion("second")
        }

        sut.register("fcm-token", "firebase-project", "android")
        flush()
        flush()

        assertTrue(http.takeRequest(2, TimeUnit.SECONDS)!!.body.unGzip().contains("\"identity_token\":\"first\""))
        assertNull(http.takeRequest(500, TimeUnit.MILLISECONDS))
        assertEquals(1, http.requestCount)
    }

    @Test
    fun `performSend skips when distinctId changes during identity token mint`() {
        val http = mockHttp(total = 1, response = MockResponse().setBody(""))
        val (sut, config, _) = getSut(http)
        val mints = java.util.concurrent.LinkedBlockingQueue<(String?) -> Unit>()
        config.pushIdentityProvider = { _, _, completion -> mints.add(completion) }

        sut.register("fcm-token", "firebase-project", "android")
        flush()

        // Identity changes (login/logout) while the token minted for the original user is still
        // outstanding.
        distinctId = "distinct-2"

        val completion = mints.poll(2, TimeUnit.SECONDS)!!
        completion("jwt-stale")
        flush()

        // The stale send must bail: nothing is posted under the new user's distinctId for a token
        // minted under the old one.
        assertNull(http.takeRequest(500, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `retryPending still fires the DELETE for a different appId even when a same-identity registration is queued`() {
        val http = mockHttp(total = 2, response = MockResponse().setBody(""))
        var connected = false
        val network =
            object : PostHogNetworkStatus {
                override fun isConnected() = connected
            }
        val (sut, config, storagePrefix) = getSut(http, networkStatus = network)

        // Log out of app-a while offline, then register for app-b under the same identity.
        sut.unregister("distinct-1", "fcm-token-a", "app-a", "android")
        sut.register("fcm-token-b", "app-b", "android")
        flush()
        assertEquals(0, http.requestCount)
        assertNotNull(readUnregister(config, pendingUnregisterFile(storagePrefix!!)))

        connected = true
        sut.retryPending()
        flush()

        // Different appId: the backend keys subscriptions per (person, app_id), so app-a's DELETE
        // is independent of the app-b registration and must still fire — unlike the same-appId case.
        val delete = http.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("DELETE", delete.method)
        assertNull(readUnregister(config, pendingUnregisterFile(storagePrefix)))
    }

    @Test
    fun `unregister keeps the intent on a 401 with no identity provider and retryPending re-attempts`() {
        val http = mockHttp(total = 1, response = MockResponse().setResponseCode(401))
        val (sut, config, storagePrefix) = getSut(http)
        // No pushIdentityProvider configured: the 401 can't be re-minted against.

        sut.unregister("distinct-1", "fcm-token", "firebase-project", "android")
        flush()

        assertEquals("DELETE", http.takeRequest().method)
        // Stops for this session, but the logout intent survives so a later launch can retry once
        // identity can be proven — dropping it here would permanently strand the device.
        assertNotNull(readUnregister(config, pendingUnregisterFile(storagePrefix!!)))

        http.enqueue(MockResponse().setBody(""))
        sut.retryPending()
        flush()

        assertEquals("DELETE", http.takeRequest(2, TimeUnit.SECONDS)!!.method)
        assertNull(readUnregister(config, pendingUnregisterFile(storagePrefix)))
    }

    @Test
    fun `register does not reset backoff when the incoming registration is identical to the current undelivered record`() {
        val http = mockHttp(total = 1, response = MockResponse().setResponseCode(500))
        val (sut, _, _) = getSut(http, maxRetries = 0)

        sut.register("fcm-token", "firebase-project", "android")
        flush()
        assertEquals("POST", http.takeRequest().method)

        // maxRetries is 0, so the single failure above already halted the session.
        // Re-registering the exact same (token, appId, platform) must not clear that halt — it's
        // register spam (e.g. FCM redelivering onNewToken), not a genuinely new registration.
        sut.register("fcm-token", "firebase-project", "android")
        flush()

        assertNull(http.takeRequest(500, TimeUnit.MILLISECONDS))
        assertEquals(1, http.requestCount)
    }

    @Test
    fun `a successful send clears a still-pending unregister for the same identity outside retryPending`() {
        val http = mockHttp(total = 1, response = MockResponse().setResponseCode(503))
        val (sut, config, storagePrefix) = getSut(http)
        distinctId = "user-A"

        // A DELETE for user-A is queued and fails (retryable), so the intent stays persisted.
        sut.unregister("user-A", "fcm-token", "firebase-project", "android")
        flush()
        assertEquals("DELETE", http.takeRequest().method)
        assertNotNull(readUnregister(config, pendingUnregisterFile(storagePrefix!!)))

        // A fresh register() for the same identity succeeds via attempt()/performSend directly
        // (not retryPending()'s drain) and must clear the still-pending same-identity DELETE.
        http.enqueue(MockResponse().setBody(""))
        sut.register("fcm-token", "firebase-project", "android")
        flush()
        assertEquals("POST", http.takeRequest(2, TimeUnit.SECONDS)!!.method)
        assertNull(readUnregister(config, pendingUnregisterFile(storagePrefix)))

        // A later retryPending() must not replay the now-cleared DELETE.
        sut.retryPending()
        flush()
        assertNull(http.takeRequest(500, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `a transport-level IOException is treated as retryable`() {
        val http = mockHttp(total = 0)
        val (sut, _, storagePrefix) = getSut(http)
        sut.retryDelayMillisPerSecond = 1L

        // Force a raw transport failure (no HTTP response at all) rather than a status code.
        http.shutdown()

        sut.register("fcm-token", "firebase-project", "android")
        flush()

        // Kept for retry, not halted: the pending file survives an unclassified send error.
        assertTrue(pendingFile(storagePrefix!!).exists())
    }

    @Test
    fun `retryPending replays a differing-identity unregister deferred during handleReset`() {
        val http = mockHttp(total = 1, response = MockResponse().setResponseCode(503))
        var connected = true
        val network =
            object : PostHogNetworkStatus {
                override fun isConnected() = connected
            }
        val (sut, config, storagePrefix) = getSut(http, networkStatus = network)

        distinctId = "user-A"
        sut.register("fcm-token", "firebase-project", "android")
        flush()
        assertEquals("POST", http.takeRequest().method)

        // reset() to a new identity while the old identity's DELETE fails/defers: the intent for
        // user-A survives, and a fresh record exists for anon-2 (currentRecord() != null).
        distinctId = "anon-2"
        http.enqueue(MockResponse().setResponseCode(503))
        http.enqueue(MockResponse().setBody(""))
        sut.handleReset("user-A")
        flush()
        assertEquals("DELETE", http.takeRequest().method)
        assertEquals("POST", http.takeRequest().method)
        assertNotNull(readUnregister(config, pendingUnregisterFile(storagePrefix!!)))

        // retryPending()'s drain must take the differing-identity replay branch (not the
        // same-identity drop), since the pending DELETE is for user-A while distinctId is anon-2.
        http.enqueue(MockResponse().setBody(""))
        sut.retryPending()
        flush()
        val replayed = http.takeRequest(2, TimeUnit.SECONDS)
        assertEquals("DELETE", replayed!!.method)
        assertEquals("user-A", parsedDistinctId(replayed))
        assertNull(readUnregister(config, pendingUnregisterFile(storagePrefix)))
    }

    @Test
    fun `a folded identical re-register does not clear a halt set by the in-flight send's own failure`() {
        val http = mockHttp(total = 1, response = MockResponse().setResponseCode(400))
        val (sut, config, storagePrefix) = getSut(http)
        var holdNext = true
        var heldCompletion: ((String?) -> Unit)? = null
        config.pushIdentityProvider = { _, _, completion ->
            if (holdNext) {
                heldCompletion = completion
            } else {
                completion(null)
            }
        }
        distinctId = "user-A"

        // register()'s identity mint is held in flight (isSending claimed, no HTTP call yet).
        sut.register("fcm-token", "firebase-project", "android")
        flush()
        assertNotNull(heldCompletion)
        holdNext = false

        // An identical re-register arrives mid-mint: isIdenticalUndelivered is true, so it folds in
        // without resetting state (resetStateOnFold = false).
        sut.register("fcm-token", "firebase-project", "android")
        flush()

        // The in-flight send's mint completes; performSend hits the 400 and halts the session.
        heldCompletion?.invoke(null)
        flush()
        assertEquals("POST", http.takeRequest(2, TimeUnit.SECONDS)!!.method)
        assertEquals(1, http.requestCount)

        // The folded replay must not have undone the halt: no second POST, and a later retryPending()
        // stays a no-op.
        sut.retryPending()
        flush()
        assertNull(http.takeRequest(500, TimeUnit.MILLISECONDS))
        assertTrue(pendingFile(storagePrefix!!).exists())
    }

    @Test
    fun `a new token folded in mid-send resets state and sends once the in-flight mint clears`() {
        val http = mockHttp(total = 2)
        val (sut, config, _) = getSut(http)
        var holdNext = true
        var heldCompletion: ((String?) -> Unit)? = null
        config.pushIdentityProvider = { _, _, completion ->
            if (holdNext) {
                heldCompletion = completion
            } else {
                completion(null)
            }
        }
        distinctId = "user-A"

        // token-A's identity mint is held in flight.
        sut.register("token-A", "firebase-project", "android")
        flush()
        assertNotNull(heldCompletion)
        holdNext = false

        // A different token folds in mid-mint: isIdenticalUndelivered is false, so it resets state
        // synchronously and asks the fold to reset again (resetStateOnFold = true).
        sut.register("token-B", "firebase-project", "android")
        flush()

        // token-A's stale mint completes; performSend detects the record changed underneath it and
        // skips sending, then releases isSending and replays the folded token-B attempt with reset state.
        heldCompletion?.invoke(null)
        flush()

        val sent = http.takeRequest(2, TimeUnit.SECONDS)
        assertEquals("POST", sent!!.method)
        assertTrue(sent.body.unGzip().contains("token-B"))
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

    @Test
    fun `register sends nothing when the app_id is not configured for the project`() {
        val http = mockHttp()
        val (sut, _, storagePrefix) = getSut(http, pushAppIds = listOf("another-project"))

        sut.register("fcm-token", "firebase-project", "android")
        flush()

        assertEquals(0, http.requestCount)
        // The record is still persisted: onPushAppIdsChanged needs a token to register once the
        // project configures push, rather than waiting for the app to hand us one again.
        assertTrue(pendingFile(storagePrefix!!).exists())
    }

    @Test
    fun `register sends when no app_id list has been published`() {
        val http = mockHttp()
        // A server older than the push config key sends nothing, and an SDK cannot tell that apart
        // from a project with push disabled. Failing closed here would silently disable push against
        // every deployment that predates the key.
        val (sut, _, _) = getSut(http, pushAppIds = null)

        sut.register("fcm-token", "firebase-project", "android")
        flush()

        assertEquals(1, http.requestCount)
    }

    @Test
    fun `register sends when the app_id is configured for the project`() {
        val http = mockHttp()
        val (sut, _, _) = getSut(http, pushAppIds = listOf("firebase-project"))

        sut.register("fcm-token", "firebase-project", "android")
        flush()

        assertEquals(1, http.requestCount)
    }

    @Test
    fun `an app_id becoming registerable clears the delivered marker and re-registers`() {
        val http = mockHttp()
        // The device registered while the project had no integration: the server answered 200 and
        // discarded the token, but the SDK recorded a delivery and stopped asking. Clearing that
        // marker is the only thing that reaches the device once the project configures push.
        var appIds: List<String>? = emptyList()
        // getSut only supplies a fixed list; this case needs one that changes mid-test.
        val (_, config, storagePrefix) = getSut(http)
        val gated =
            PostHogPushSubscriptionManager(config, PostHogApi(config), executor, { distinctId }, { appIds })

        gated.register("fcm-token", "firebase-project", "android")
        flush()
        assertEquals(0, http.requestCount)

        appIds = listOf("firebase-project")
        gated.onPushAppIdsChanged(setOf("firebase-project"))
        flush()

        assertNotNull(http.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("distinct-1", readRecord(config, pendingFile(storagePrefix!!))?.deliveredForDistinctId)
    }

    @Test
    fun `an unrelated app_id becoming registerable does not re-register`() {
        val http = mockHttp()
        val (sut, _, _) = getSut(http, pushAppIds = listOf("firebase-project"))

        sut.register("fcm-token", "firebase-project", "android")
        flush()
        assertEquals(1, http.requestCount)

        // Firing on every config load would put the request back on every launch, which is exactly
        // what the delivered marker exists to prevent.
        sut.onPushAppIdsChanged(setOf("some-other-project"))
        flush()

        assertEquals(1, http.requestCount)
    }

}

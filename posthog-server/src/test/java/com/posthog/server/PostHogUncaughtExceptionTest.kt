package com.posthog.server

import com.posthog.errortracking.PostHogErrorTrackingAutoCaptureIntegration
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests for the opt-in server uncaught-exception capture wired via
 * [PostHogConfig.captureUncaughtExceptions].
 *
 * The global [Thread.setDefaultUncaughtExceptionHandler] and the integration's process-global
 * install flag are shared JVM state, so every test restores the original handler and closes the
 * client to keep the suite isolated.
 */
internal class PostHogUncaughtExceptionTest {
    private var originalHandler: Thread.UncaughtExceptionHandler? = null

    @BeforeTest
    fun setUp() {
        originalHandler = Thread.getDefaultUncaughtExceptionHandler()
    }

    @AfterTest
    fun tearDown() {
        // Defensively clear the process-global install flag if a test threw before closing, so a
        // leaked handler can't turn later tests' installs into no-ops.
        (Thread.getDefaultUncaughtExceptionHandler() as? PostHogErrorTrackingAutoCaptureIntegration)
            ?.uninstall()
        Thread.setDefaultUncaughtExceptionHandler(originalHandler)
    }

    private fun startServer(): MockWebServer =
        MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(200))
            start()
        }

    @Test
    fun `disabled by default does not install an uncaught handler`() {
        val sentinel = Thread.UncaughtExceptionHandler { _, _ -> }
        Thread.setDefaultUncaughtExceptionHandler(sentinel)

        val postHog =
            PostHog.with(
                PostHogConfig.builder(TEST_API_KEY)
                    .host("https://example.com")
                    .build(),
            )

        // The default handler must be untouched when the option is off.
        assertSame(sentinel, Thread.getDefaultUncaughtExceptionHandler())

        postHog.close()
    }

    @Test
    fun `enabled installs handler and chains the previous one, restored on close`() {
        var chainedThread: Thread? = null
        var chainedThrowable: Throwable? = null
        val sentinel =
            Thread.UncaughtExceptionHandler { thread, throwable ->
                chainedThread = thread
                chainedThrowable = throwable
            }
        Thread.setDefaultUncaughtExceptionHandler(sentinel)

        val mockServer = startServer()
        val postHog =
            PostHog.with(
                PostHogConfig.builder(TEST_API_KEY)
                    .host(mockServer.url("/").toString())
                    .captureUncaughtExceptions(true)
                    .build(),
            )

        // Our integration is now the default handler and it is not the sentinel.
        val installed = Thread.getDefaultUncaughtExceptionHandler()
        assertTrue(
            installed is PostHogErrorTrackingAutoCaptureIntegration,
            "Expected the PostHog integration to be installed as the default handler",
        )

        // Simulate an uncaught exception.
        val thread = Thread.currentThread()
        val boom = RuntimeException("boom")
        installed.uncaughtException(thread, boom)

        // The previous handler is chained after capture.
        assertSame(thread, chainedThread)
        assertSame(boom, chainedThrowable)

        // Close removes our handler and restores the sentinel.
        postHog.close()
        assertSame(sentinel, Thread.getDefaultUncaughtExceptionHandler())

        mockServer.shutdown()
    }

    @Test
    fun `uncaught exception is captured as a fatal, unhandled exception event`() {
        val mockServer = startServer()
        // Default flushAt (100) on purpose: the crash path's blocking flush is what delivers the
        // event, so a low threshold would mask whether that flush works at all.
        val postHog =
            PostHog.with(
                PostHogConfig.builder(TEST_API_KEY)
                    .host(mockServer.url("/").toString())
                    .captureUncaughtExceptions(true)
                    .build(),
            )

        val handler = Thread.getDefaultUncaughtExceptionHandler()
        assertTrue(handler is PostHogErrorTrackingAutoCaptureIntegration)

        handler.uncaughtException(Thread.currentThread(), IllegalStateException("kaboom"))

        // Already sent when the handler returned, so this should not have to wait.
        val request = mockServer.takeRequest(1, TimeUnit.SECONDS)
        assertNotNull(request, "Expected the crash event to be flushed before the handler returned")

        val batch = request.parseBatch()
        val exceptionEvent = batch.findEvent("\$exception")
        assertNotNull(exceptionEvent, "Expected an \$exception event")

        val props = batch.eventProperties("\$exception")
        assertEquals("fatal", props["\$exception_level"], "Uncaught exceptions must be fatal")

        @Suppress("UNCHECKED_CAST")
        val exceptionList = props["\$exception_list"] as? List<Map<String, Any?>>
        assertNotNull(exceptionList, "Expected a \$exception_list")
        assertTrue(exceptionList.isNotEmpty())

        @Suppress("UNCHECKED_CAST")
        val mechanism = exceptionList.first()["mechanism"] as? Map<String, Any?>
        assertNotNull(mechanism, "Expected a mechanism on the first exception item")
        assertEquals(false, mechanism["handled"], "Uncaught exceptions must be marked handled=false")
        assertEquals(
            "onuncaughtexception",
            mechanism["type"],
            "Uncaught exceptions must carry the canonical onuncaughtexception mechanism",
        )
        assertEquals(
            "jvm.uncaught_exception_handler",
            props["\$exception_source"],
            "Uncaught exceptions must name the concrete runtime hook in \$exception_source",
        )

        postHog.close()
        mockServer.shutdown()
    }

    @Test
    fun `repeated setup keeps handler ownership so close still restores the previous handler`() {
        val sentinel = Thread.UncaughtExceptionHandler { _, _ -> }
        Thread.setDefaultUncaughtExceptionHandler(sentinel)

        val mockServer = startServer()
        val config =
            PostHogConfig.builder(TEST_API_KEY)
                .host(mockServer.url("/").toString())
                .flushAt(1)
                .captureUncaughtExceptions(true)
                .build()
        val postHog = PostHog.with(config)

        assertTrue(
            Thread.getDefaultUncaughtExceptionHandler() is PostHogErrorTrackingAutoCaptureIntegration,
        )

        // A second setup on the same instance is a no-op for the base client; it must not replace
        // the owning integration with a non-owning one, or close() could no longer uninstall.
        postHog.setup(config)

        postHog.close()
        assertSame(sentinel, Thread.getDefaultUncaughtExceptionHandler())

        mockServer.shutdown()
    }

    @Test
    fun `a rejected repeated setup does not install a handler`() {
        val sentinel = Thread.UncaughtExceptionHandler { _, _ -> }
        Thread.setDefaultUncaughtExceptionHandler(sentinel)

        val mockServer = startServer()
        val postHog =
            PostHog.with(
                PostHogConfig.builder(TEST_API_KEY)
                    .host(mockServer.url("/").toString())
                    .build(),
            )

        assertSame(sentinel, Thread.getDefaultUncaughtExceptionHandler())

        // The base client ignores a second setup and keeps its original config, so opting in through
        // that rejected call must not install a handler either.
        postHog.setup(
            PostHogConfig.builder(TEST_API_KEY)
                .host(mockServer.url("/").toString())
                .captureUncaughtExceptions(true)
                .build(),
        )

        assertSame(
            sentinel,
            Thread.getDefaultUncaughtExceptionHandler(),
            "A rejected setup must not install the uncaught handler",
        )

        postHog.close()
        mockServer.shutdown()
    }

    @Test
    fun `double setup does not install a second handler`() {
        val mockServer = startServer()
        val postHog =
            PostHog.with(
                PostHogConfig.builder(TEST_API_KEY)
                    .host(mockServer.url("/").toString())
                    .flushAt(1)
                    .captureUncaughtExceptions(true)
                    .build(),
            )

        val firstHandler = Thread.getDefaultUncaughtExceptionHandler()
        assertTrue(firstHandler is PostHogErrorTrackingAutoCaptureIntegration)

        // A second client that also opts in must not stack a second handler on top of ours (the
        // process-global install guard makes the second install a no-op).
        val second =
            PostHog.with(
                PostHogConfig.builder(TEST_API_KEY)
                    .host(mockServer.url("/").toString())
                    .flushAt(1)
                    .captureUncaughtExceptions(true)
                    .build(),
            )

        assertSame(
            firstHandler,
            Thread.getDefaultUncaughtExceptionHandler(),
            "The second setup must not replace the already-installed handler",
        )

        second.close()
        postHog.close()
        mockServer.shutdown()
    }

    @Test
    fun `crash event is flushed even while its enqueue is still pending`() {
        // Makes the crash-path race deterministic instead of hoping for a scheduling order. The queue
        // thread is parked inside the gated warmup batch, so the crash capture's fatal task is provably
        // still pending when the handler flushes, and with flushAt above one and a 600s flush interval
        // nothing else can deliver the crash event. The handler runs on its own thread so the test can
        // assert it is still blocked inside the capture while the task is pending — the inline flush
        // it used to do returned immediately, having read an empty queue, and the event died with the
        // JVM.
        val batches = CopyOnWriteArrayList<BatchRequest>()
        val warmupReceived = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val mockServer = MockWebServer()
        mockServer.dispatcher =
            object : Dispatcher() {
                private val batchRequests = AtomicInteger(0)

                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path?.contains("/batch") == true) {
                        batches.add(request.parseBatch())
                        if (batchRequests.getAndIncrement() == 0) {
                            warmupReceived.countDown()
                            // Holds the queue thread until the test opens the gate.
                            gate.await(5, TimeUnit.SECONDS)
                        }
                    }
                    return MockResponse().setResponseCode(200)
                }
            }
        mockServer.start()

        val postHog =
            PostHog.with(
                PostHogConfig.builder(TEST_API_KEY)
                    .host(mockServer.url("/").toString())
                    .flushAt(2)
                    // Long enough that only the crash path can deliver the event within the test.
                    .flushIntervalSeconds(600)
                    .captureUncaughtExceptions(true)
                    .build(),
            )

        // Two events reach flushAt(2), so the queue thread flushes and parks in the gated request.
        postHog.capture(DISTINCT_ID, "warmup_1")
        postHog.capture(DISTINCT_ID, "warmup_2")
        assertTrue(
            warmupReceived.await(5, TimeUnit.SECONDS),
            "Expected the warmup batch to reach the server and park the queue thread",
        )

        val handler = Thread.getDefaultUncaughtExceptionHandler()
        assertTrue(handler is PostHogErrorTrackingAutoCaptureIntegration)
        val crashThread =
            Thread {
                handler.uncaughtException(Thread.currentThread(), IllegalStateException("kaboom"))
            }.apply {
                isDaemon = true
                start()
            }

        // The only timed wait on this thread's crash path is the fatal add's bounded await, so
        // TIMED_WAITING while the queue thread is parked means the enqueue-and-drain task is queued
        // behind the parked warmup flush. The old inline flush never blocked: the
        // thread just finishes, and this assertion fails.
        val parkedBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (crashThread.isAlive &&
            crashThread.state != Thread.State.TIMED_WAITING &&
            System.nanoTime() < parkedBy
        ) {
            Thread.sleep(1)
        }
        assertTrue(
            crashThread.isAlive && crashThread.state == Thread.State.TIMED_WAITING,
            "Expected the handler to still be blocked inside the crash flush",
        )
        assertEquals(1, batches.size, "The crash event cannot have been sent yet")

        gate.countDown()
        crashThread.join(TimeUnit.SECONDS.toMillis(5))
        assertFalse(
            crashThread.isAlive,
            "Expected the handler to return once the crash flush completed",
        )

        // The handler only returns after its flush ran, so the crash batch is already on the wire.
        assertEquals(2, batches.size, "Expected the crash event to be flushed before returning")
        assertNotNull(
            batches[1].findEvent("\$exception"),
            "Expected an \$exception event in the crash batch",
        )

        postHog.close()
        mockServer.shutdown()
    }

    @Test
    fun `enabled works with no remote config present`() {
        // The server SDK never fetches remote config; the local-only gate must still install.
        val sentinel = Thread.UncaughtExceptionHandler { _, _ -> }
        Thread.setDefaultUncaughtExceptionHandler(sentinel)

        val mockServer = startServer()
        val postHog =
            PostHog.with(
                PostHogConfig.builder(TEST_API_KEY)
                    .host(mockServer.url("/").toString())
                    .captureUncaughtExceptions(true)
                    .build(),
            )

        assertTrue(
            Thread.getDefaultUncaughtExceptionHandler() is PostHogErrorTrackingAutoCaptureIntegration,
            "Local-only gate should install even without any remote config",
        )
        assertFalse(Thread.getDefaultUncaughtExceptionHandler() === sentinel)

        postHog.close()
        mockServer.shutdown()
    }
}

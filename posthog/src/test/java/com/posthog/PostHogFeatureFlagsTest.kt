package com.posthog

import com.posthog.internal.PostHogBatchEvent
import com.posthog.internal.PostHogContext
import com.posthog.internal.PostHogMemoryPreferences
import com.posthog.internal.PostHogSerializer
import com.posthog.internal.PostHogThreadFactory
import okhttp3.mockwebserver.MockResponse
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PostHogFeatureFlagsTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    @get:Rule
    val servers = TestHttpServers()

    private fun mockHttp(
        total: Int = 1,
        response: MockResponse = MockResponse().setBody(""),
    ) = servers.mockHttp(total, response)

    private val clients = mutableListOf<PostHogInterface>()

    private val queueExecutor =
        Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestQueue"))
    private val replayQueueExecutor =
        Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestReplayQueue"))
    private val remoteConfigExecutor =
        Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestRemoteConfig"))
    private val cachedEventsExecutor =
        Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestCachedEvents"))
    private val serializer = PostHogSerializer(PostHogConfig(PROJECT_TOKEN))
    private lateinit var config: PostHogConfig

    @Suppress("DEPRECATION")
    fun getSut(
        host: String,
        flushAt: Int = 1,
        storagePrefix: String = tmpDir.newFolder().absolutePath,
        optOut: Boolean = false,
        preloadFeatureFlags: Boolean = true,
        // setup always requests /config; skip it when the test doesn't preload flags
        reloadFeatureFlags: Boolean = preloadFeatureFlags,
        sendFeatureFlagEvent: Boolean = true,
        reuseAnonymousId: Boolean = false,
        integration: PostHogIntegration? = null,
        cachePreferences: PostHogMemoryPreferences = PostHogMemoryPreferences(),
        beforeSend: PostHogBeforeSend? = null,
        evaluationContexts: List<String>? = null,
        context: PostHogContext? = null,
        featureFlagsListenerExecutor: Executor? = null,
    ): PostHogInterface {
        config =
            PostHogConfig(PROJECT_TOKEN, host).apply {
                // for testing
                this.flushAt = flushAt
                this.storagePrefix = File(storagePrefix, "events").absolutePath
                this.replayStoragePrefix = File(storagePrefix, "snapshots").absolutePath
                this.optOut = optOut
                this.preloadFeatureFlags = preloadFeatureFlags
                if (integration != null) {
                    addIntegration(integration)
                }
                this.sendFeatureFlagEvent = sendFeatureFlagEvent
                this.reuseAnonymousId = reuseAnonymousId
                this.cachePreferences = cachePreferences
                this.evaluationContexts = evaluationContexts
                if (beforeSend != null) {
                    addBeforeSend(beforeSend)
                }
                this.errorTrackingConfig.inAppIncludes.add("com.posthog")
                this.context = context
                this.featureFlagsListenerExecutor = featureFlagsListenerExecutor
            }
        return PostHog.withInternal(
            config,
            queueExecutor,
            replayQueueExecutor,
            remoteConfigExecutor,
            cachedEventsExecutor,
            reloadFeatureFlags,
        ).also { clients.add(it) }
    }

    @AfterTest
    fun `set down`() {
        clients.forEach { it.close() }
        listOf(queueExecutor, replayQueueExecutor, remoteConfigExecutor, cachedEventsExecutor).forEach { it.shutdownAndAwaitTermination() }
        tmpDir.root.deleteRecursively()
    }

    @Test
    fun `check function getFeatureFlag where sendFeatureFlagEvent=true and config_sendFeatureFlagEvent=false`() {
        val file = File("src/test/resources/json/basic-flags-with-non-active-flags.json")
        val responseFlagsApi = file.readText()

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseFlagsApi),
            )
        http.enqueue(
            MockResponse()
                .setBody(""),
        )
        val url = http.url("/")
        val sut = getSut(url.toString(), preloadFeatureFlags = false, sendFeatureFlagEvent = false)

        sut.reloadFeatureFlags()

        remoteConfigExecutor.shutdownAndAwaitTermination()

        // remove from the http queue
        http.takeRequest()

        sut.getFeatureFlag("splashScreenName", sendFeatureFlagEvent = true)

        queueExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()
        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        val theEvent = batch.batch.firstOrNull()
        assertEquals("\$feature_flag_called", theEvent?.event)
        assertEquals(1, batch.batch.size)
        sut.close()
    }

    @Test
    fun `check function getFeatureFlag where sendFeatureFlagEvent=false and config_sendFeatureFlagEvent=false`() {
        val file = File("src/test/resources/json/basic-flags-with-non-active-flags.json")
        val responseFlagsApi = file.readText()

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseFlagsApi),
            )
        http.enqueue(
            MockResponse()
                .setBody(""),
        )
        val url = http.url("/")
        val sut = getSut(url.toString(), preloadFeatureFlags = false, sendFeatureFlagEvent = false)

        sut.reloadFeatureFlags()
        remoteConfigExecutor.shutdownAndAwaitTermination()

        // remove from the http queue
        http.takeRequest()
        sut.getFeatureFlag("splashScreenName", sendFeatureFlagEvent = false)
        sut.capture("test_event")

        queueExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()
        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        val theEvent = batch.batch.firstOrNull()
        assertEquals(1, batch.batch.size)
        assertEquals("test_event", theEvent?.event)
        sut.close()
    }

    @Test
    fun `check function getFeatureFlag where sendFeatureFlagEvent=null and config_sendFeatureFlagEvent=false`() {
        val file = File("src/test/resources/json/basic-flags-with-non-active-flags.json")
        val responseFlagsApi = file.readText()

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseFlagsApi),
            )
        http.enqueue(
            MockResponse()
                .setBody(""),
        )
        val url = http.url("/")
        val sut =
            getSut(
                url.toString(),
                preloadFeatureFlags = false,
                sendFeatureFlagEvent = false,
            )

        sut.reloadFeatureFlags()

        remoteConfigExecutor.shutdownAndAwaitTermination()

        // remove from the http queue
        http.takeRequest()

        sut.getFeatureFlag("splashScreenName", sendFeatureFlagEvent = null)
        sut.capture("test_event")

        queueExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()
        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        val theEvent = batch.batch.firstOrNull()
        assertEquals("test_event", theEvent?.event)
        assertEquals(1, batch.batch.size)
        sut.close()
    }

    @Test
    fun `check function getFeatureFlag where sendFeatureFlagEvent=false and config_sendFeatureFlagEvent=true`() {
        val file = File("src/test/resources/json/basic-flags-with-non-active-flags.json")
        val responseFlagsApi = file.readText()

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseFlagsApi),
            )
        http.enqueue(
            MockResponse()
                .setBody(""),
        )
        val url = http.url("/")
        val sut = getSut(url.toString(), preloadFeatureFlags = false, sendFeatureFlagEvent = true)

        sut.reloadFeatureFlags()

        remoteConfigExecutor.shutdownAndAwaitTermination()

        // remove from the http queue
        http.takeRequest()

        sut.getFeatureFlag("splashScreenName", sendFeatureFlagEvent = false)
        sut.capture("test_event")
        queueExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()
        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        val theEvent = batch.batch.firstOrNull()
        assertNotEquals("\$feature_flag_called", theEvent?.event)
        assertEquals("test_event", theEvent?.event)
        assertEquals(1, batch.batch.size)
        sut.close()
    }

    @Test
    fun `check function getFeatureFlag where sendFeatureFlagEvent=true and config_sendFeatureFlagEvent=true`() {
        val file = File("src/test/resources/json/basic-flags-with-non-active-flags.json")
        val responseFlagsApi = file.readText()

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseFlagsApi),
            )
        http.enqueue(
            MockResponse()
                .setBody(""),
        )
        val url = http.url("/")
        val sut = getSut(url.toString(), preloadFeatureFlags = false, sendFeatureFlagEvent = true)

        sut.reloadFeatureFlags()

        remoteConfigExecutor.shutdownAndAwaitTermination()

        // remove from the http queue
        http.takeRequest()

        sut.getFeatureFlag("splashScreenName", sendFeatureFlagEvent = true)
        queueExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()
        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        val theEvent = batch.batch.firstOrNull()
        assertEquals("\$feature_flag_called", theEvent?.event)
        assertEquals(1, batch.batch.size)
        sut.close()
    }

    @Test
    fun `check function getFeatureFlag where sendFeatureFlagEvent=null and config_sendFeatureFlagEvent=true`() {
        val file = File("src/test/resources/json/basic-flags-with-non-active-flags.json")
        val responseFlagsApi = file.readText()

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseFlagsApi),
            )
        http.enqueue(
            MockResponse()
                .setBody(""),
        )
        val url = http.url("/")
        val sut =
            getSut(
                url.toString(),
                preloadFeatureFlags = false,
                sendFeatureFlagEvent = true,
            )

        sut.reloadFeatureFlags()

        remoteConfigExecutor.shutdownAndAwaitTermination()

        // remove from the http queue
        http.takeRequest()

        sut.getFeatureFlag("splashScreenName", sendFeatureFlagEvent = null)

        queueExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()
        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        val theEvent = batch.batch.firstOrNull()
        assertEquals("\$feature_flag_called", theEvent?.event)
        assertEquals(1, batch.batch.size)
        sut.close()
    }

    @Test
    fun `getFeatureFlag with non-existent flag sends defaultValue in event`() {
        val file = File("src/test/resources/json/basic-flags-with-non-active-flags.json")
        val responseFlagsApi = file.readText()

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseFlagsApi),
            )
        http.enqueue(
            MockResponse()
                .setBody(""),
        )
        val url = http.url("/")
        val sut = getSut(url.toString(), preloadFeatureFlags = false, sendFeatureFlagEvent = true)

        sut.reloadFeatureFlags()

        remoteConfigExecutor.shutdownAndAwaitTermination()

        // remove from the http queue
        http.takeRequest()

        val defaultValue = "my-default"
        val result = sut.getFeatureFlag("nonExistentFlag", defaultValue)

        queueExecutor.shutdownAndAwaitTermination()

        // Function should return the defaultValue
        assertEquals(defaultValue, result)

        val request = http.takeRequest()
        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        val theEvent = batch.batch.firstOrNull()
        assertEquals("\$feature_flag_called", theEvent?.event)
        // The event's $feature_flag_response should be the defaultValue, not null
        assertEquals(defaultValue, theEvent?.properties!!["\$feature_flag_response"])
        assertEquals("nonExistentFlag", theEvent.properties!!["\$feature_flag"])
        sut.close()
    }

    @Test
    fun `reloadFeatureFlags invokes callback when not enabled`() {
        val http = mockHttp()
        val url = http.url("/")
        val sut = getSut(url.toString(), preloadFeatureFlags = false)
        sut.close()

        var result: PostHogFeatureFlagsLoaded? = null
        sut.reloadFeatureFlags { result = it }

        // isEnabled() is false after close(), so reloadFeatureFlags early-returns. The
        // callback must still fire (synchronously) rather than leave awaiting callers hanging.
        assertTrue(result!!.errorsLoading)
        assertTrue(result!!.flags.isEmpty())
        assertTrue(result!!.variants.isEmpty())
    }

    @Test
    fun `onFeatureFlags listener receives each load until unsubscribed`() {
        val flags = File("src/test/resources/json/basic-flags-with-non-active-flags.json").readText()
        val http = mockHttp(response = MockResponse().setBody(flags))
        http.enqueue(MockResponse().setResponseCode(500))
        http.enqueue(MockResponse().setBody(flags))
        val sut = getSut(http.url("/").toString(), preloadFeatureFlags = false)
        val enabled = mapOf("4535-funnel-bar-viz" to true, "splashScreenName" to "SplashV2")

        val updates = LinkedBlockingQueue<PostHogFeatureFlagsLoaded>()
        val subscription = sut.onFeatureFlags { updates.add(it) }

        sut.reloadFeatureFlags()
        val loaded = updates.poll(5, TimeUnit.SECONDS)!!
        assertEquals(enabled, loaded.variants)
        assertFalse(loaded.errorsLoading)

        sut.reloadFeatureFlags()
        val failed = updates.poll(5, TimeUnit.SECONDS)!!
        assertEquals(enabled, failed.variants)
        assertTrue(failed.errorsLoading)

        subscription.unsubscribe()
        val reloaded = CountDownLatch(1)
        sut.reloadFeatureFlags { reloaded.countDown() }
        assertTrue(reloaded.await(5, TimeUnit.SECONDS))
        assertNull(updates.poll())
    }

    @Test
    fun `onFeatureFlags reports and replays the persisted flags when the first load fails`() {
        val persisted = mapOf<String, Any>("beta-ui" to true)
        val cache = PostHogMemoryPreferences().apply { setValue("featureFlags", persisted) }
        val http = mockHttp(response = MockResponse().setResponseCode(500))
        val sut = getSut(http.url("/").toString(), preloadFeatureFlags = false, cachePreferences = cache)

        val updates = LinkedBlockingQueue<PostHogFeatureFlagsLoaded>()
        sut.onFeatureFlags { updates.add(it) }
        sut.reloadFeatureFlags()

        val failed = updates.poll(5, TimeUnit.SECONDS)!!
        assertTrue(failed.errorsLoading)
        assertEquals(persisted, failed.variants)

        val lateUpdates = LinkedBlockingQueue<PostHogFeatureFlagsLoaded>()
        sut.onFeatureFlags { lateUpdates.add(it) }
        assertEquals(persisted, lateUpdates.poll(5, TimeUnit.SECONDS)!!.variants)
    }

    @Test
    fun `onFeatureFlags replays loaded flags to a late listener`() {
        val flags = File("src/test/resources/json/basic-flags-with-non-active-flags.json").readText()
        val http = mockHttp(response = MockResponse().setBody(flags))
        val sut = getSut(http.url("/").toString(), preloadFeatureFlags = false)
        val reloaded = CountDownLatch(1)
        sut.reloadFeatureFlags { reloaded.countDown() }
        assertTrue(reloaded.await(5, TimeUnit.SECONDS))

        val updates = LinkedBlockingQueue<PostHogFeatureFlagsLoaded>()
        sut.onFeatureFlags { updates.add(it) }

        assertEquals(setOf("4535-funnel-bar-viz", "splashScreenName"), updates.poll(5, TimeUnit.SECONDS)!!.flags.toSet())
    }

    @Test
    fun `onFeatureFlags listener survives close and setup`() {
        val flags = File("src/test/resources/json/basic-flags-with-non-active-flags.json").readText()
        val http = mockHttp(response = MockResponse().setBody(flags))
        val sut = getSut(http.url("/").toString(), preloadFeatureFlags = false)
        val updates = LinkedBlockingQueue<PostHogFeatureFlagsLoaded>()
        sut.onFeatureFlags { updates.add(it) }

        sut.close()
        sut.setup(config)
        sut.reloadFeatureFlags()

        assertFalse(updates.poll(5, TimeUnit.SECONDS)!!.errorsLoading)
    }

    @Test
    fun `onFeatureFlags drops a load that finishes after close`() {
        val flags = File("src/test/resources/json/basic-flags-with-non-active-flags.json").readText()
        val http = mockHttp(response = MockResponse().setBody(flags).setHeadersDelay(500, TimeUnit.MILLISECONDS))
        val sut = getSut(http.url("/").toString(), preloadFeatureFlags = false)
        val updates = LinkedBlockingQueue<PostHogFeatureFlagsLoaded>()
        sut.onFeatureFlags { updates.add(it) }
        val reloaded = CountDownLatch(1)
        sut.reloadFeatureFlags { reloaded.countDown() }
        http.takeRequest(5, TimeUnit.SECONDS)

        sut.close()
        assertTrue(reloaded.await(5, TimeUnit.SECONDS))
        sut.setup(config)
        sut.onFeatureFlags { updates.add(it) }

        remoteConfigExecutor.submit {}.get(5, TimeUnit.SECONDS)
        assertNull(updates.poll())
    }

    @Test
    fun `closing from an integration's install drops setup's load for listeners`() {
        val flags = File("src/test/resources/json/basic-flags-with-non-active-flags.json").readText()
        val http = mockHttp(total = 2, response = MockResponse().setBody(flags))
        val updates = LinkedBlockingQueue<PostHogFeatureFlagsLoaded>()
        val integration =
            object : PostHogIntegration {
                override fun install(postHog: PostHogInterface) {
                    postHog.onFeatureFlags { updates.add(it) }
                    postHog.close()
                }
            }
        val sut = getSut(http.url("/").toString(), integration = integration)

        // setup's load runs on the remote config thread; close() cleared the snapshot it would refill
        remoteConfigExecutor.submit {}.get(5, TimeUnit.SECONDS)
        sut.onFeatureFlags { updates.add(it) }
        remoteConfigExecutor.submit {}.get(5, TimeUnit.SECONDS)
        assertNull(updates.poll())
    }

    @Test
    fun `onFeatureFlags listener is not invoked by a reload while disabled`() {
        val sut = getSut(mockHttp().url("/").toString(), preloadFeatureFlags = false)
        val updates = LinkedBlockingQueue<PostHogFeatureFlagsLoaded>()
        sut.onFeatureFlags { updates.add(it) }
        sut.close()

        var result: PostHogFeatureFlagsLoaded? = null
        sut.reloadFeatureFlags { result = it }

        remoteConfigExecutor.submit {}.get(5, TimeUnit.SECONDS)
        assertTrue(result!!.errorsLoading)
        assertNull(updates.poll())
    }

    @Test
    fun `onFeatureFlags listeners run on the listener executor`() {
        val flags = File("src/test/resources/json/basic-flags-with-non-active-flags.json").readText()
        val http = mockHttp(response = MockResponse().setBody(flags))
        val listenerExecutor = Executors.newSingleThreadExecutor { Thread(it, "listener-thread") }
        val sut = getSut(http.url("/").toString(), preloadFeatureFlags = false, featureFlagsListenerExecutor = listenerExecutor)

        val threads = LinkedBlockingQueue<String>()
        sut.onFeatureFlags { threads.add(Thread.currentThread().name) }
        sut.reloadFeatureFlags()
        assertEquals("listener-thread", threads.poll(5, TimeUnit.SECONDS))

        sut.onFeatureFlags { threads.add(Thread.currentThread().name) }
        assertEquals("listener-thread", threads.poll(5, TimeUnit.SECONDS))
        listenerExecutor.shutdownAndAwaitTermination()
    }

    @Test
    fun `onFeatureFlags delivers a queued update once and drops it after unsubscribe`() {
        val flags = File("src/test/resources/json/basic-flags-with-non-active-flags.json").readText()
        val http = mockHttp(response = MockResponse().setBody(flags))
        val queued = LinkedBlockingQueue<Runnable>()
        val sut = getSut(http.url("/").toString(), preloadFeatureFlags = false, featureFlagsListenerExecutor = Executor { queued.add(it) })

        val kept = LinkedBlockingQueue<PostHogFeatureFlagsLoaded>()
        val dropped = LinkedBlockingQueue<PostHogFeatureFlagsLoaded>()
        sut.onFeatureFlags { kept.add(it) }
        val subscription = sut.onFeatureFlags { dropped.add(it) }
        val reloaded = CountDownLatch(1)
        sut.reloadFeatureFlags { reloaded.countDown() }
        assertTrue(reloaded.await(5, TimeUnit.SECONDS))
        remoteConfigExecutor.submit {}.get(5, TimeUnit.SECONDS)

        // Each listener has its replay and the load's delivery queued, both carrying the loaded flags.
        subscription.unsubscribe()
        generateSequence { queued.poll() }.forEach { it.run() }

        assertEquals(1, kept.size)
        assertFalse(kept.single().errorsLoading)
        assertTrue(dropped.isEmpty())
    }
}

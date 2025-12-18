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
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

internal class PostHogFeatureFlagsTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    private val queueExecutor =
        Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestQueue"))
    private val replayQueueExecutor =
        Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestReplayQueue"))
    private val remoteConfigExecutor =
        Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestRemoteConfig"))
    private val cachedEventsExecutor =
        Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestCachedEvents"))
    private val serializer = PostHogSerializer(PostHogConfig(API_KEY))
    private lateinit var config: PostHogConfig

    @Suppress("DEPRECATION")
    fun getSut(
        host: String,
        flushAt: Int = 1,
        storagePrefix: String = tmpDir.newFolder().absolutePath,
        optOut: Boolean = false,
        preloadFeatureFlags: Boolean = true,
        reloadFeatureFlags: Boolean = true,
        sendFeatureFlagEvent: Boolean = true,
        reuseAnonymousId: Boolean = false,
        integration: PostHogIntegration? = null,
        remoteConfig: Boolean = false,
        cachePreferences: PostHogMemoryPreferences = PostHogMemoryPreferences(),
        propertiesSanitizer: PostHogPropertiesSanitizer? = null,
        beforeSend: PostHogBeforeSend? = null,
        evaluationEnvironments: List<String>? = null,
        context: PostHogContext? = null,
    ): PostHogInterface {
        config =
            PostHogConfig(API_KEY, host).apply {
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
                this.propertiesSanitizer = propertiesSanitizer
                this.evaluationEnvironments = evaluationEnvironments
                this.remoteConfig = remoteConfig
                if (beforeSend != null) {
                    addBeforeSend(beforeSend)
                }
                this.errorTrackingConfig.inAppIncludes.add("com.posthog")
                this.context = context
            }
        return PostHog.withInternal(
            config,
            queueExecutor,
            replayQueueExecutor,
            remoteConfigExecutor,
            cachedEventsExecutor,
            reloadFeatureFlags,
        )
    }

    @AfterTest
    fun `set down`() {
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
}

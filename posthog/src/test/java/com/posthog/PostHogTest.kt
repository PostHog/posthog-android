package com.posthog

import com.posthog.internal.PostHogBatchEvent
import com.posthog.internal.PostHogContext
import com.posthog.internal.PostHogMemoryPreferences
import com.posthog.internal.PostHogPreferences.Companion.GROUPS
import com.posthog.internal.PostHogPreferences.Companion.GROUP_PROPERTIES_FOR_FLAGS
import com.posthog.internal.PostHogPreferences.Companion.PERSON_PROPERTIES_FOR_FLAGS
import com.posthog.internal.PostHogPreferences.Companion.SESSION_REPLAY
import com.posthog.internal.PostHogPrintLogger
import com.posthog.internal.PostHogSendCachedEventsIntegration
import com.posthog.internal.PostHogSerializer
import com.posthog.internal.PostHogSessionManager
import com.posthog.internal.PostHogThreadFactory
import com.posthog.internal.errortracking.PostHogThrowable
import com.posthog.vendor.uuid.TimeBasedEpochGenerator
import okhttp3.mockwebserver.MockResponse
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PostHogTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    private val queueExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestQueue"))
    private val replayQueueExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestReplayQueue"))
    private val remoteConfigExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestRemoteConfig"))
    private val cachedEventsExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestCachedEvents"))
    private val serializer = PostHogSerializer(PostHogConfig(API_KEY))
    private lateinit var config: PostHogConfig

    private val file = File("src/test/resources/json/flags-v1/basic-flags-no-errors.json")
    private val responseFlagsApi = file.readText()

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
        evaluationContexts: List<String>? = null,
        context: PostHogContext? = null,
        personProfiles: PersonProfiles = PersonProfiles.IDENTIFIED_ONLY,
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
                this.evaluationContexts = evaluationContexts
                this.remoteConfig = remoteConfig
                if (beforeSend != null) {
                    addBeforeSend(beforeSend)
                }
                this.errorTrackingConfig.inAppIncludes.add("com.posthog")
                this.context = context
                this.personProfiles = personProfiles
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
    fun `optOut is disabled by default`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString())

        assertFalse(sut.isOptOut())

        sut.close()
    }

    @Test
    fun `optOut is enabled if given`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), optOut = true)

        assertTrue(sut.isOptOut())

        sut.close()
    }

    @Test
    fun `setup adds integration by default`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString())

        assertTrue(config.integrations.first() is PostHogSendCachedEventsIntegration)

        sut.close()
    }

    @Test
    fun `install integrations`() {
        val http = mockHttp()
        val url = http.url("/")

        val integration = FakePostHogIntegration()

        val sut = getSut(url.toString(), integration = integration)

        assertTrue(integration.installed)

        sut.close()
    }

    @Test
    fun `uninstall integrations`() {
        val http = mockHttp()
        val url = http.url("/")

        val integration = FakePostHogIntegration()

        val sut = getSut(url.toString(), integration = integration)

        sut.close()

        assertFalse(integration.installed)
    }

    @Test
    fun `setup sets in memory cached preferences if not given`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString())

        assertTrue(config.cachePreferences is PostHogMemoryPreferences)

        sut.close()
    }

    @Test
    fun `preload feature flags if enabled`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString())

        remoteConfigExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()
        assertEquals(1, http.requestCount)
        assertEquals("/flags/?v=2&config=true", request.path)

        sut.close()
    }

    @Test
    fun `reload feature flags and call the callback`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false)

        var reloaded = false

        sut.reloadFeatureFlags {
            reloaded = true
        }

        remoteConfigExecutor.shutdownAndAwaitTermination()

        assertTrue(reloaded)

        sut.close()
    }

    @Test
    fun `preload remote config if enabled`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), remoteConfig = true, preloadFeatureFlags = false)

        remoteConfigExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()
        assertEquals(1, http.requestCount)
        assertEquals("/array/${API_KEY}/config", request.path)

        sut.close()
    }

    @Test
    fun `preload remote config and flags if enabled`() {
        val file = File("src/test/resources/json/basic-remote-config.json")
        val responseText = file.readText()

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseText),
            )
        http.enqueue(
            MockResponse()
                .setBody(responseFlagsApi),
        )
        val url = http.url("/")

        val sut = getSut(url.toString(), remoteConfig = true)

        remoteConfigExecutor.shutdownAndAwaitTermination()

        val remoteConfigRequest = http.takeRequest()

        assertEquals(2, http.requestCount)
        assertEquals("/array/${API_KEY}/config", remoteConfigRequest.path)

        val flagsApiRequest = http.takeRequest()
        assertEquals("/flags/?v=2&config=true", flagsApiRequest.path)

        sut.close()
    }

    @Test
    fun `preload remote config but no flags`() {
        val file = File("src/test/resources/json/basic-remote-config-no-flags.json")
        val responseText = file.readText()

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseText),
            )
        http.enqueue(
            MockResponse()
                .setBody(responseFlagsApi),
        )
        val url = http.url("/")

        val sut = getSut(url.toString(), remoteConfig = true)

        remoteConfigExecutor.shutdownAndAwaitTermination()

        val remoteConfigRequest = http.takeRequest()

        assertEquals(1, http.requestCount)
        assertEquals("/array/${API_KEY}/config", remoteConfigRequest.path)

        sut.close()
    }

    @Test
    fun `isFeatureEnabled returns value after reloaded`() {
        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseFlagsApi),
            )
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false)

        sut.reloadFeatureFlags()

        remoteConfigExecutor.shutdownAndAwaitTermination()

        assertTrue(sut.isFeatureEnabled("4535-funnel-bar-viz"))

        sut.close()
    }

    @Test
    fun `getFeatureFlag returns the value after reloaded`() {
        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseFlagsApi),
            )
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false)

        sut.reloadFeatureFlags()

        remoteConfigExecutor.shutdownAndAwaitTermination()

        assertTrue(sut.getFeatureFlag("4535-funnel-bar-viz") as Boolean)

        sut.close()
    }

    @Test
    fun `getFeatureFlag captures feature flag event if enabled`() {
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

        val sut = getSut(url.toString(), preloadFeatureFlags = false)

        sut.reloadFeatureFlags()

        remoteConfigExecutor.shutdownAndAwaitTermination()

        // remove from the http queue
        http.takeRequest()

        assertTrue(sut.getFeatureFlag("4535-funnel-bar-viz") as Boolean)
        assertFalse(sut.getFeatureFlag("IAmInactive") as Boolean)
        assertEquals("SplashV2", sut.getFeatureFlag("splashScreenName") as String)

        queueExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()
        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        val theEvent = batch.batch.first()
        assertEquals("\$feature_flag_called", theEvent.event)
        assertNotNull(theEvent.distinctId)
        assertNotNull(theEvent.timestamp)
        assertNotNull(theEvent.uuid)

        assertEquals(true, theEvent.properties!!["\$feature/4535-funnel-bar-viz"])
        assertEquals(false, theEvent.properties!!["\$feature/IAmInactive"])
        assertEquals("SplashV2", theEvent.properties!!["\$feature/splashScreenName"])
        assertEquals("171d83c3-4ac2-4bff-961d-efe3a0c3539c", theEvent.properties!!["\$feature_flag_request_id"])
        assertEquals(4535, theEvent.properties!!["\$feature_flag_id"])
        assertEquals(2, theEvent.properties!!["\$feature_flag_version"])
        assertEquals("Matched condition set 3", theEvent.properties!!["\$feature_flag_reason"])

        @Suppress("UNCHECKED_CAST")
        val theFlags = theEvent.properties!!["\$active_feature_flags"] as List<String>
        assertTrue(theFlags.contains("4535-funnel-bar-viz"))
        assertTrue(theFlags.contains("splashScreenName"))
        assertFalse(theFlags.contains("IAmInactive"))

        assertEquals("4535-funnel-bar-viz", theEvent.properties!!["\$feature_flag"])
        assertEquals(true, theEvent.properties!!["\$feature_flag_response"])

        sut.close()
    }

    @Test
    fun `isFeatureEnabled captures feature flag event if enabled`() {
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

        val sut = getSut(url.toString(), preloadFeatureFlags = false)

        sut.reloadFeatureFlags()

        remoteConfigExecutor.shutdownAndAwaitTermination()

        // remove from the http queue
        http.takeRequest()

        assertTrue(sut.isFeatureEnabled("4535-funnel-bar-viz"))

        queueExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()
        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        val theEvent = batch.batch.first()
        assertEquals("\$feature_flag_called", theEvent.event)
        assertNotNull(theEvent.distinctId)
        assertNotNull(theEvent.timestamp)
        assertNotNull(theEvent.uuid)

        assertEquals(true, theEvent.properties!!["\$feature/4535-funnel-bar-viz"])

        @Suppress("UNCHECKED_CAST")
        val theFlags = theEvent.properties!!["\$active_feature_flags"] as List<String>
        assertTrue(theFlags.contains("4535-funnel-bar-viz"))

        assertEquals("4535-funnel-bar-viz", theEvent.properties!!["\$feature_flag"])
        assertEquals(true, theEvent.properties!!["\$feature_flag_response"])

        sut.close()
    }

    @Test
    fun `isFeatureEnabled captures feature flag variant response if enabled`() {
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

        val sut = getSut(url.toString(), preloadFeatureFlags = false)

        sut.reloadFeatureFlags()

        remoteConfigExecutor.shutdownAndAwaitTermination()

        // remove from the http queue
        http.takeRequest()

        assertTrue(sut.isFeatureEnabled("splashScreenName"))

        queueExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()
        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        val theEvent = batch.batch.first()
        assertEquals("\$feature_flag_called", theEvent.event)
        assertNotNull(theEvent.distinctId)
        assertNotNull(theEvent.timestamp)
        assertNotNull(theEvent.uuid)

        assertEquals("SplashV2", theEvent.properties!!["\$feature/splashScreenName"])

        @Suppress("UNCHECKED_CAST")
        val theFlags = theEvent.properties!!["\$active_feature_flags"] as List<String>
        assertTrue(theFlags.contains("splashScreenName"))

        assertEquals("splashScreenName", theEvent.properties!!["\$feature_flag"])
        assertEquals("SplashV2", theEvent.properties!!["\$feature_flag_response"])

        sut.close()
    }

    @Test
    fun `getFeatureFlagPayload returns the value after reloaded`() {
        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseFlagsApi),
            )
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false)

        sut.reloadFeatureFlags()

        remoteConfigExecutor.shutdownAndAwaitTermination()

        assertTrue(sut.getFeatureFlagPayload("4535-funnel-bar-viz") as Boolean)

        sut.close()
    }

    @Test
    fun `do not preload feature flags if disabled`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false)

        remoteConfigExecutor.shutdownAndAwaitTermination()

        assertEquals(0, http.requestCount)

        sut.close()
    }

    @Test
    fun `includes evaluation_contexts in feature flag request when configured`() {
        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseFlagsApi),
            )
        val url = http.url("/")

        val sut =
            getSut(
                url.toString(),
                preloadFeatureFlags = false,
                evaluationContexts = listOf("production", "web", "checkout"),
            )

        sut.reloadFeatureFlags()

        remoteConfigExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()
        val body = request.body.unGzip()
        val flagsRequest = serializer.deserialize<Map<String, Any>>(body.reader())

        @Suppress("UNCHECKED_CAST")
        val evaluationContexts = flagsRequest["evaluation_contexts"] as? List<String>
        assertEquals(listOf("production", "web", "checkout"), evaluationContexts)

        sut.close()
    }

    @Test
    fun `does not capture any event if optOut`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false)

        sut.optOut()

        sut.capture(
            EVENT,
            DISTINCT_ID,
            props,
            userProperties = userProps,
            userPropertiesSetOnce = userPropsOnce,
            groups = groups,
        )

        queueExecutor.shutdownAndAwaitTermination()

        assertEquals(0, http.requestCount)

        sut.close()
    }

    @Test
    fun `captures an event if optIn back again`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false)

        sut.optOut()

        sut.capture(
            EVENT,
            DISTINCT_ID,
            props,
            userProperties = userProps,
            userPropertiesSetOnce = userPropsOnce,
            groups = groups,
        )

        queueExecutor.awaitExecution()

        sut.optIn()

        sut.capture(
            EVENT,
            DISTINCT_ID,
            props,
            userProperties = userProps,
            userPropertiesSetOnce = userPropsOnce,
            groups = groups,
        )

        queueExecutor.shutdownAndAwaitTermination()

        assertEquals(1, http.requestCount)

        sut.close()
    }

    @Test
    fun `captures an event`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false)

        sut.capture(
            EVENT,
            DISTINCT_ID,
            props,
            userProperties = userProps,
            userPropertiesSetOnce = userPropsOnce,
            groups = groups,
        )

        queueExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()

        assertEquals(1, http.requestCount)
        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        assertEquals(API_KEY, batch.apiKey)
        assertNotNull(batch.sentAt)

        val theEvent = batch.batch.first()
        assertEquals(EVENT, theEvent.event)
        assertEquals(DISTINCT_ID, theEvent.distinctId)
        assertNotNull(theEvent.timestamp)
        assertNotNull(theEvent.uuid)
        assertEquals("value", theEvent.properties!!["prop"] as String)
        assertEquals(userProps, theEvent.properties!!["\$set"])
        assertEquals(userPropsOnce, theEvent.properties!!["\$set_once"])
        assertEquals(groups, theEvent.properties!!["\$groups"])

        sut.close()
    }

    @Test
    fun `capture uses generated distinctId if not given`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false)

        sut.capture(
            EVENT,
            properties = props,
            userProperties = userProps,
            userPropertiesSetOnce = userPropsOnce,
            groups = groups,
        )

        queueExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()

        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        val theEvent = batch.batch.first()

        assertNotNull(theEvent.distinctId)

        sut.close()
    }

    @Test
    fun `captures an event with a custom timestamp`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false)

        val customTimestamp = date // Use the predefined test date from Utils.kt

        sut.capture(
            EVENT,
            DISTINCT_ID,
            timestamp = customTimestamp,
        )

        queueExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()

        assertEquals(1, http.requestCount)
        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        assertEquals(API_KEY, batch.apiKey)
        assertNotNull(batch.sentAt)

        val theEvent = batch.batch.first()
        assertNotNull(theEvent.timestamp)
        assertNotNull(theEvent.uuid)
        assertEquals(EVENT, theEvent.event)
        assertEquals(DISTINCT_ID, theEvent.distinctId)
        assertEquals(theEvent.timestamp, customTimestamp)

        sut.close()
    }

    @Test
    fun `captures an identify event`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false, reloadFeatureFlags = false)

        sut.identify(
            DISTINCT_ID,
            userProperties = userProps,
            userPropertiesSetOnce = userPropsOnce,
        )

        queueExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()

        assertEquals(1, http.requestCount)
        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        val theEvent = batch.batch.first()
        assertEquals("\$identify", theEvent.event)
        assertEquals(DISTINCT_ID, theEvent.distinctId)
        assertNotNull(theEvent.properties!!["\$anon_distinct_id"])
        assertEquals(userProps, theEvent.properties!!["\$set"])
        assertEquals(userPropsOnce, theEvent.properties!!["\$set_once"])

        sut.close()
    }

    @Test
    fun `does not capture an identify event with invalid distinct id`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false, reloadFeatureFlags = false)

        sut.identify(
            "   ",
            userProperties = userProps,
            userPropertiesSetOnce = userPropsOnce,
        )

        queueExecutor.shutdownAndAwaitTermination()

        assertEquals(0, http.requestCount)

        sut.close()
    }

    @Test
    fun `does not capture an identify event if identified`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false, reloadFeatureFlags = false)

        sut.identify(
            DISTINCT_ID,
            userProperties = userProps,
            userPropertiesSetOnce = userPropsOnce,
        )

        sut.identify(
            "anotherDistinctId",
        )

        queueExecutor.shutdownAndAwaitTermination()

        assertEquals(1, http.requestCount)

        sut.close()
    }

    @Test
    fun `captures a set event if identified`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false, reloadFeatureFlags = false, flushAt = 2)

        sut.identify(
            DISTINCT_ID,
            userProperties = userProps,
            userPropertiesSetOnce = userPropsOnce,
        )

        val userProps = mapOf("user1" to "theResult")
        val userPropsOnce = mapOf("logged" to false)

        sut.identify(
            DISTINCT_ID,
            userProperties = userProps,
            userPropertiesSetOnce = userPropsOnce,
        )

        queueExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()

        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        val theEvent = batch.batch.last()

        assertEquals("\$set", theEvent.event)
        assertEquals(userProps, theEvent.properties!!["\$set"])
        assertEquals(userPropsOnce, theEvent.properties!!["\$set_once"])

        sut.close()
    }

    @Test
    fun `does not capture duplicate set event if identify called with same properties`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false, reloadFeatureFlags = false, flushAt = 2)

        // First identify - captures $identify event
        sut.identify(
            DISTINCT_ID,
        )

        // Second identify with same distinctId - captures $set event (first time with these props)
        sut.identify(
            DISTINCT_ID,
            userProperties = userProps,
            userPropertiesSetOnce = userPropsOnce,
        )

        // Third identify with same distinctId and same properties - should NOT capture another $set event
        sut.identify(
            DISTINCT_ID,
            userProperties = userProps,
            userPropertiesSetOnce = userPropsOnce,
        )

        queueExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()

        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        // Should only have 2 events: $identify and $set (the duplicate $set should be ignored)
        assertEquals(2, batch.batch.size)
        assertEquals("\$identify", batch.batch[0].event)
        assertEquals("\$set", batch.batch[1].event)

        sut.close()
    }

    @Test
    fun `captures set event after properties change in identify`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false, reloadFeatureFlags = false, flushAt = 3)

        // First identify
        sut.identify(
            DISTINCT_ID,
        )

        // Second identify with same distinctId - captures $set event
        sut.identify(
            DISTINCT_ID,
            userProperties = userProps,
            userPropertiesSetOnce = userPropsOnce,
        )

        // Third identify with same distinctId but DIFFERENT properties - should capture another $set event
        val newUserProps = mapOf("different" to "value")
        sut.identify(
            DISTINCT_ID,
            userProperties = newUserProps,
            userPropertiesSetOnce = userPropsOnce,
        )

        queueExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()

        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        // Should have 3 events: $identify, $set, $set (different props)
        assertEquals(3, batch.batch.size)
        assertEquals("\$identify", batch.batch[0].event)
        assertEquals("\$set", batch.batch[1].event)
        assertEquals("\$set", batch.batch[2].event)
        assertEquals(newUserProps, batch.batch[2].properties!!["\$set"])

        sut.close()
    }

    @Test
    fun `does not capture a set event if different user`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false, reloadFeatureFlags = false)

        sut.identify(
            DISTINCT_ID,
            userProperties = userProps,
            userPropertiesSetOnce = userPropsOnce,
        )

        val userProps = mapOf("user1" to "theResult")
        val userPropsOnce = mapOf("logged" to false)

        sut.identify(
            "different user",
            userProperties = userProps,
            userPropertiesSetOnce = userPropsOnce,
        )

        queueExecutor.shutdownAndAwaitTermination()

        queueExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()

        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        val theEvent = batch.batch.last()

        assertEquals(1, batch.batch.size)
        assertEquals("\$identify", theEvent.event)

        sut.close()
    }

    @Test
    fun `captures an identify event post reset`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false, reloadFeatureFlags = false)

        sut.identify(
            DISTINCT_ID,
            userProperties = userProps,
            userPropertiesSetOnce = userPropsOnce,
        )

        sut.reset()

        sut.identify(
            DISTINCT_ID,
            userProperties = userProps,
            userPropertiesSetOnce = userPropsOnce,
        )

        queueExecutor.shutdownAndAwaitTermination()

        assertEquals(2, http.requestCount)

        sut.close()
    }

    @Test
    fun `sets is_identified property for identified user`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false, reloadFeatureFlags = false)

        sut.identify(
            DISTINCT_ID,
            userProperties = userProps,
            userPropertiesSetOnce = userPropsOnce,
        )

        queueExecutor.shutdownAndAwaitTermination()

        assertEquals(1, http.requestCount)

        val request = http.takeRequest()
        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        val theEvent = batch.batch.first()

        assertTrue(theEvent.properties!!["\$is_identified"] as Boolean)

        sut.close()
    }

    @Test
    fun `sets is_identified property for non identified user`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false, reloadFeatureFlags = false)

        sut.capture(
            "test",
            userProperties = userProps,
            userPropertiesSetOnce = userPropsOnce,
        )

        queueExecutor.shutdownAndAwaitTermination()

        assertEquals(1, http.requestCount)

        val request = http.takeRequest()
        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        val theEvent = batch.batch.first()

        assertFalse(theEvent.properties!!["\$is_identified"] as Boolean)

        sut.close()
    }

    @Test
    fun `captures an alias event`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false)

        sut.alias(
            "theAlias",
        )

        queueExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()

        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        val theEvent = batch.batch.first()
        assertEquals("\$create_alias", theEvent.event)
        assertNotNull(theEvent.distinctId)
        assertEquals("theAlias", theEvent.properties!!["alias"] as String)

        sut.close()
    }

    @Test
    fun `creates a group`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false)

        sut.group("theType", "theKey", groupProps)

        queueExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()

        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        val theEvent = batch.batch.first()
        assertEquals("\$groupidentify", theEvent.event)
        assertNotNull(theEvent.distinctId)
        assertEquals("theType", theEvent.properties!!["\$group_type"] as String)
        assertEquals("theKey", theEvent.properties!!["\$group_key"] as String)
        assertEquals(groupProps, theEvent.properties!!["\$group_set"])

        sut.close()
    }

    @Test
    fun `merges group`() {
        val http = mockHttp()
        val url = http.url("/")

        val myPrefs = PostHogMemoryPreferences()
        val groups = mutableMapOf("theType2" to "theKey2")
        myPrefs.setValue(GROUPS, groups)
        val sut = getSut(url.toString(), flushAt = 2, preloadFeatureFlags = false, cachePreferences = myPrefs, reloadFeatureFlags = false)

        sut.group("theType", "theKey", groupProps)

        sut.capture("test", groups = mutableMapOf("theType3" to "theKey3"))

        queueExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()

        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        val theEvent = batch.batch.last()

        val allGroups = mutableMapOf<String, Any>()
        allGroups.putAll(groups)
        allGroups["theType"] = "theKey"
        allGroups["theType3"] = "theKey3"
        assertEquals(allGroups, theEvent.properties!!["\$groups"])

        sut.close()
    }

    @Test
    fun `registers a property for the next events`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false)

        sut.register("newRegister", true)

        sut.capture(
            EVENT,
            DISTINCT_ID,
            props,
            userProperties = userProps,
            userPropertiesSetOnce = userPropsOnce,
            groups = groups,
        )

        queueExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()

        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        val theEvent = batch.batch.first()

        assertTrue(theEvent.properties!!["newRegister"] as Boolean)

        sut.close()
    }

    @Test
    fun `registers ignore internal keys`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false)

        sut.register("version", "123")

        sut.capture(
            EVENT,
            DISTINCT_ID,
            props,
            userProperties = userProps,
            userPropertiesSetOnce = userPropsOnce,
            groups = groups,
        )

        queueExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()

        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        val theEvent = batch.batch.first()

        assertNull(theEvent.properties!!["version"])

        sut.close()
    }

    @Test
    fun `unregister removes property`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false)

        sut.register("newRegister", true)

        sut.unregister("newRegister")

        sut.capture(
            EVENT,
            DISTINCT_ID,
            props,
            userProperties = userProps,
            userPropertiesSetOnce = userPropsOnce,
            groups = groups,
        )

        queueExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()

        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        val theEvent = batch.batch.first()

        assertNull(theEvent.properties!!["newRegister"])

        sut.close()
    }

    @Test
    fun `merges properties`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false)

        sut.capture(
            EVENT,
            DISTINCT_ID,
            props,
            userProperties = userProps,
            userPropertiesSetOnce = userPropsOnce,
            groups = groups,
        )

        queueExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()

        assertEquals(1, http.requestCount)
        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        assertEquals(API_KEY, batch.apiKey)
        assertNotNull(batch.sentAt)

        val theEvent = batch.batch.first()
        assertEquals(EVENT, theEvent.event)
        assertEquals(DISTINCT_ID, theEvent.distinctId)
        assertNotNull(theEvent.timestamp)
        assertNotNull(theEvent.uuid)
        assertEquals("value", theEvent.properties!!["prop"] as String)
        assertEquals(userProps, theEvent.properties!!["\$set"])
        assertEquals(userPropsOnce, theEvent.properties!!["\$set_once"])
        assertEquals(groups, theEvent.properties!!["\$groups"])

        sut.close()
    }

    @Test
    fun `close not capture events after closing`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false)

        sut.close()

        sut.capture(
            EVENT,
            DISTINCT_ID,
            props,
            userProperties = userProps,
            userPropertiesSetOnce = userPropsOnce,
            groups = groups,
        )

        queueExecutor.shutdownAndAwaitTermination()

        assertEquals(0, http.requestCount)
    }

    @Test
    fun `reads legacy shared prefs and set distinctId and AnonId`() {
        val http = mockHttp()
        val url = http.url("/")

        val myPrefs = PostHogMemoryPreferences()
        myPrefs.setValue(API_KEY, """{"anonymousId":"anonId","distinctId":"disId"}""")
        val sut = getSut(url.toString(), preloadFeatureFlags = false, cachePreferences = myPrefs)

        sut.capture(
            EVENT,
            properties = props,
            userProperties = userProps,
            userPropertiesSetOnce = userPropsOnce,
            groups = groups,
        )

        queueExecutor.shutdownAndAwaitTermination()

        assertNull(myPrefs.getValue(API_KEY))

        val request = http.takeRequest()

        assertEquals(1, http.requestCount)
        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        val theEvent = batch.batch.first()
        assertEquals("disId", theEvent.distinctId)

        sut.close()
    }

    @Test
    fun `distinctId returns same value after identify`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false, reloadFeatureFlags = false)

        sut.identify("myNewDistinctId")

        queueExecutor.shutdownAndAwaitTermination()

        assertEquals("myNewDistinctId", sut.distinctId())
    }

    @Test
    fun `reuse anonymousId when flag reuseAnonymousId is true`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false, reloadFeatureFlags = false, reuseAnonymousId = true)

        val anonymousId = sut.distinctId()
        sut.reset()

        queueExecutor.shutdownAndAwaitTermination()

        assertEquals(anonymousId, sut.distinctId())
    }

    @Test
    fun `anonymousId is not overwritten on re-identify when reuseAnonymousId is true`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false, reloadFeatureFlags = false, reuseAnonymousId = true)

        val anonymousId = sut.distinctId()
        sut.identify("myDistinctId")
        sut.identify("myNewDistinctId")
        sut.reset()

        queueExecutor.shutdownAndAwaitTermination()

        assertEquals(anonymousId, sut.distinctId())
    }

    @Test
    fun `do not link anonymousId on identify when reuseAnonymousId is true`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false, reloadFeatureFlags = false, reuseAnonymousId = true)

        sut.identify("myDistinctId")

        queueExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()

        assertEquals(1, http.requestCount)
        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        val theEvent = batch.batch.first()

        assertNull(theEvent.properties!!["\$anon_distinct_id"])
    }

    @Test
    fun `do not send feature flags called event twice`() {
        val file = File("src/test/resources/json/basic-flags-no-errors.json")
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

        val sut = getSut(url.toString(), preloadFeatureFlags = false)

        sut.reloadFeatureFlags()

        remoteConfigExecutor.shutdownAndAwaitTermination()

        // remove from the http queue
        http.takeRequest()

        assertTrue(sut.getFeatureFlag("4535-funnel-bar-viz") as Boolean)

        queueExecutor.awaitExecution()

        val request = http.takeRequest()
        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        val theEvent = batch.batch.first()
        assertEquals("\$feature_flag_called", theEvent.event)

        assertEquals(true, theEvent.properties!!["\$feature/4535-funnel-bar-viz"])

        @Suppress("UNCHECKED_CAST")
        val theFlags = theEvent.properties!!["\$active_feature_flags"] as List<String>
        assertTrue(theFlags.contains("4535-funnel-bar-viz"))

        assertEquals("4535-funnel-bar-viz", theEvent.properties!!["\$feature_flag"])
        assertEquals(true, theEvent.properties!!["\$feature_flag_response"])

        assertTrue(sut.getFeatureFlag("4535-funnel-bar-viz") as Boolean)

        queueExecutor.awaitExecution()

        // just the 2 events
        assertEquals(2, http.requestCount)

        sut.close()
    }

    @Test
    fun `allows for modification of the uuid generation mechanism`() {
        val expected = TimeBasedEpochGenerator.generate()
        val config =
            PostHogConfig(API_KEY, getAnonymousId = {
                assertNotEquals(it, expected, "Expect two unique UUIDs")
                expected
            })
        // now generate an event
        val sut = PostHog.with(config)
        assertEquals(expected.toString(), sut.distinctId(), "It should use the injected uuid instead")
    }

    @Test
    fun `enables debug mode`() {
        val config = PostHogConfig(API_KEY)
        val sut = PostHog.with(config)

        sut.debug(true)

        assertTrue(config.debug)

        sut.close()
    }

    @Test
    fun `disables debug mode`() {
        val config =
            PostHogConfig(API_KEY).apply {
                debug = true
            }
        val sut = PostHog.with(config)

        sut.debug(false)

        assertFalse(config.debug)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `sanitize properties`() {
        val http = mockHttp()
        val url = http.url("/")

        val propertiesSanitizer =
            PostHogPropertiesSanitizer { properties ->
                properties.apply {
                    remove("prop")
                }
            }

        val sut = getSut(url.toString(), preloadFeatureFlags = false, propertiesSanitizer = propertiesSanitizer)

        sut.capture(
            EVENT,
            DISTINCT_ID,
            // contains "prop"
            props,
            userProperties = userProps,
            userPropertiesSetOnce = userPropsOnce,
            groups = groups,
        )

        queueExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()

        assertEquals(1, http.requestCount)
        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        assertEquals(API_KEY, batch.apiKey)
        assertNotNull(batch.sentAt)

        val theEvent = batch.batch.first()
        assertNull(theEvent.properties!!["prop"])

        sut.close()
    }

    @Test
    fun `reset session id when reset is called`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false, reloadFeatureFlags = false)

        sut.capture(
            EVENT,
            DISTINCT_ID,
            props,
            userProperties = userProps,
            userPropertiesSetOnce = userPropsOnce,
            groups = groups,
        )

        queueExecutor.awaitExecution()

        var request = http.takeRequest()

        assertEquals(1, http.requestCount)
        var content = request.body.unGzip()
        var batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        var theEvent = batch.batch.first()
        val currentSessionId = theEvent.properties!!["\$session_id"]
        assertNotNull(currentSessionId)

        sut.reset()

        sut.capture(
            EVENT,
            DISTINCT_ID,
            props,
            userProperties = userProps,
            userPropertiesSetOnce = userPropsOnce,
            groups = groups,
        )

        queueExecutor.shutdownAndAwaitTermination()

        request = http.takeRequest()

        assertEquals(2, http.requestCount)
        content = request.body.unGzip()
        batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        theEvent = batch.batch.first()
        val newSessionId = theEvent.properties!!["\$session_id"]
        assertNotNull(newSessionId)

        assertTrue(currentSessionId != newSessionId)

        sut.close()
    }

    @Test
    fun `capture snapshot event should set session id`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false, reloadFeatureFlags = false)

        sut.capture(
            "\$snapshot",
            DISTINCT_ID,
            props,
            userProperties = userProps,
            userPropertiesSetOnce = userPropsOnce,
            groups = groups,
        )

        replayQueueExecutor.awaitExecution()

        val request = http.takeRequest()

        assertEquals(1, http.requestCount)
        val content = request.body.unGzip()
        val batch = serializer.deserialize<List<PostHogEvent>>(content.reader())

        val theEvent = batch.first()
        val currentSessionId = theEvent.properties!!["\$session_id"]
        assertNotNull(currentSessionId)
        assertEquals("\$snapshot", theEvent.event)

        sut.reset()

        sut.close()
    }

    @Test
    fun `reset reloads flags as anon user`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false)

        sut.reset()

        remoteConfigExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()
        assertEquals(1, http.requestCount)
        assertEquals("/flags/?v=2&config=true", request.path)

        sut.close()
    }

    @Test
    fun `logger uses System out by default`() {
        val http = mockHttp()
        val url = http.url("/")
        val sut = getSut(url.toString())

        assertTrue(config.logger is PostHogPrintLogger)

        sut.close()
    }

    @Test
    fun `isSessionReplayActive returns false if disabled`() {
        val http = mockHttp()
        val url = http.url("/")
        val sut = getSut(url.toString())

        sut.close()

        assertFalse(sut.isSessionReplayActive())
    }

    @Test
    fun `isSessionReplayActive returns false if sessionReplayHandler returns false`() {
        val http = mockHttp()
        val url = http.url("/")
        val sut = getSut(url.toString(), integration = PostHogSessionReplayHandlerFake(false))

        assertFalse(sut.isSessionReplayActive())

        sut.close()
    }

    @Test
    fun `isSessionReplayActive returns false if PostHogSessionManager returns false`() {
        val http = mockHttp()
        val url = http.url("/")
        val sut = getSut(url.toString(), integration = PostHogSessionReplayHandlerFake(true))

        PostHogSessionManager.endSession()

        assertFalse(sut.isSessionReplayActive())

        sut.close()
    }

    @Test
    fun `isSessionReplayActive returns true if session is running`() {
        val http = mockHttp()
        val url = http.url("/")
        val sut = getSut(url.toString(), integration = PostHogSessionReplayHandlerFake(true))

        assertTrue(sut.isSessionReplayActive())

        sut.close()
    }

    @Test
    fun `startSessionReplay does nothing if disabled`() {
        val http = mockHttp()
        val url = http.url("/")
        val integration = PostHogSessionReplayHandlerFake(true)
        val sut = getSut(url.toString(), integration = integration)

        sut.close()

        sut.startSessionReplay()

        assertFalse(integration.startCalled)
    }

    @Test
    fun `startSessionReplay does nothing if isSessionReplayFlagEnabled returns false`() {
        val http = mockHttp()
        val url = http.url("/")
        val integration = PostHogSessionReplayHandlerFake(true)

        val sut = getSut(url.toString(), preloadFeatureFlags = false, integration = integration)

        sut.startSessionReplay()

        assertFalse(integration.startCalled)
    }

    @Test
    fun `startSessionReplay does nothing if sessionReplayHandler returns false`() {
        val http = mockHttp()
        val url = http.url("/")
        val integration = PostHogSessionReplayHandlerFake(true)

        val myPrefs = PostHogMemoryPreferences()
        val sessionReplayConfig = emptyMap<String, String>()
        myPrefs.setValue(SESSION_REPLAY, sessionReplayConfig)

        val sut = getSut(url.toString(), cachePreferences = myPrefs, preloadFeatureFlags = false, integration = integration)

        sut.startSessionReplay()

        assertFalse(integration.startCalled)
    }

    @Test
    fun `startSessionReplay starts session with resumeCurrent`() {
        val http = mockHttp()
        val url = http.url("/")
        val integration = PostHogSessionReplayHandlerFake(false)

        val myPrefs = PostHogMemoryPreferences()
        val sessionReplayConfig = emptyMap<String, String>()
        myPrefs.setValue(SESSION_REPLAY, sessionReplayConfig)

        val sut = getSut(url.toString(), cachePreferences = myPrefs, preloadFeatureFlags = false, integration = integration)

        val currentSessionId = sut.getSessionId()

        sut.startSessionReplay()

        assertEquals(currentSessionId, sut.getSessionId())
        assertTrue(integration.startCalled)
        assertTrue(integration.resumeCurrent == true)
    }

    @Test
    fun `startSessionReplay starts session with resumeCurrent false`() {
        val http = mockHttp()
        val url = http.url("/")
        val integration = PostHogSessionReplayHandlerFake(false)

        val myPrefs = PostHogMemoryPreferences()
        val sessionReplayConfig = emptyMap<String, String>()
        myPrefs.setValue(SESSION_REPLAY, sessionReplayConfig)

        val sut = getSut(url.toString(), cachePreferences = myPrefs, preloadFeatureFlags = false, integration = integration)

        val currentSessionId = sut.getSessionId()

        sut.startSessionReplay(resumeCurrent = false)

        assertNotEquals(currentSessionId, sut.getSessionId())
        assertTrue(integration.startCalled)
        assertTrue(integration.resumeCurrent == false)
    }

    @Test
    fun `stopSessionReplay stops session if active`() {
        val http = mockHttp()
        val url = http.url("/")
        val integration = PostHogSessionReplayHandlerFake(false)

        val myPrefs = PostHogMemoryPreferences()
        val sessionReplayConfig = emptyMap<String, String>()
        myPrefs.setValue(SESSION_REPLAY, sessionReplayConfig)

        val sut = getSut(url.toString(), cachePreferences = myPrefs, preloadFeatureFlags = false, integration = integration)

        sut.startSessionReplay()
        sut.stopSessionReplay()

        assertTrue(integration.stopCalled)
    }

    @Test
    fun `captureException captures exception with correct properties`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false)

        val causeException = Exception("I am the cause")
        val exception = RuntimeException("Test exception message", causeException)
        val additionalProperties = mapOf("custom_key" to "custom_value")

        sut.captureException(exception, additionalProperties)

        queueExecutor.shutdownAndAwaitTermination()

        assertEquals(1, http.requestCount)

        val request = http.takeRequest()
        val content = request.body.unGzip()
        val batchEvents = serializer.deserialize<PostHogBatchEvent>(content.reader())

        val event = batchEvents.batch.first()
        assertEquals(PostHogEventName.EXCEPTION.event, event.event)

        val properties = event.properties ?: emptyMap()

        // Verify basic exception properties
        assertEquals("error", properties["\$exception_level"])
        assertEquals("custom_value", properties["custom_key"])

        // Verify exception list structure - should contain both main exception and cause
        val exceptionList = properties["\$exception_list"] as List<*>
        assertEquals(2, exceptionList.size)

        // The ThrowableCoercer processes the exception chain with main exception first, then cause
        val exceptions = exceptionList.map { it as Map<*, *> }

        // The first exception should be the main RuntimeException
        val mainException = exceptions[0]
        assertEquals("RuntimeException", mainException["type"])
        assertEquals("Test exception message", mainException["value"])

        // The second exception should be the cause Exception
        val causeExceptionData = exceptions[1]
        assertEquals("Exception", causeExceptionData["type"])
        assertEquals("I am the cause", causeExceptionData["value"])

        val threadId = Thread.currentThread().id
        // Verify both exceptions have the required structure
        // Check main exception structure
        assertEquals("RuntimeException", mainException["type"])
        assertEquals(threadId, (mainException["thread_id"] as Number).toLong())

        // Check cause exception structure
        assertEquals("Exception", causeExceptionData["type"])
        assertEquals(threadId, (causeExceptionData["thread_id"] as Number).toLong())

        // Verify mechanism structure for main exception
        val mechanism = mainException["mechanism"] as Map<*, *>
        assertEquals(true, mechanism["handled"])
        assertEquals(false, mechanism["synthetic"])
        assertEquals("generic", mechanism["type"])

        // Verify mechanism structure for cause exception
        val causeMechanism = causeExceptionData["mechanism"] as Map<*, *>
        assertEquals(true, causeMechanism["handled"])
        assertEquals(false, causeMechanism["synthetic"])
        assertEquals("generic", causeMechanism["type"])

        // Verify stack trace structure for main exception
        val stackTraceMainException = mainException["stacktrace"] as Map<*, *>
        assertEquals("raw", stackTraceMainException["type"])

        val framesMainException = stackTraceMainException["frames"] as List<*>
        assertTrue(framesMainException.isNotEmpty())

        // Verify first frame structure
        val firstFrameMainException = framesMainException.first() as Map<*, *>
        assertTrue(firstFrameMainException.containsKey("module"))
        assertTrue(firstFrameMainException.containsKey("function"))
        assertEquals("java", firstFrameMainException["platform"])
        assertTrue(firstFrameMainException["in_app"] as Boolean)
        assertTrue(firstFrameMainException["lineno"] is Number)

        // Verify stack trace structure for cause exception
        val stackTraceCauseException = causeExceptionData["stacktrace"] as Map<*, *>
        assertEquals("raw", stackTraceCauseException["type"])

        val framesCauseException = stackTraceCauseException["frames"] as List<*>
        assertTrue(framesCauseException.isNotEmpty())

        // Verify first frame structure
        val firstFrameCauseException = framesCauseException.first() as Map<*, *>
        assertTrue(firstFrameCauseException.containsKey("module"))
        assertTrue(firstFrameCauseException.containsKey("function"))
        assertEquals("java", firstFrameCauseException["platform"])
        assertTrue(firstFrameCauseException["in_app"] as Boolean)
        assertTrue(firstFrameCauseException["lineno"] is Number)

        sut.close()
    }

    @Test
    fun `captureException unwraps and captures exception with correct properties`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false)

        val causeException = RuntimeException("Test exception message")
        val thread = Thread()
        val exception = PostHogThrowable(causeException, thread = thread)
        val additionalProperties = mapOf("custom_key" to "custom_value")

        sut.captureException(exception, additionalProperties)

        queueExecutor.shutdownAndAwaitTermination()

        assertEquals(1, http.requestCount)

        val request = http.takeRequest()
        val content = request.body.unGzip()
        val batchEvents = serializer.deserialize<PostHogBatchEvent>(content.reader())

        val event = batchEvents.batch.first()
        assertEquals(PostHogEventName.EXCEPTION.event, event.event)

        val properties = event.properties ?: emptyMap()

        // Verify basic exception properties
        assertEquals("fatal", properties["\$exception_level"])
        assertEquals("custom_value", properties["custom_key"])

        // Verify exception list structure - should contain both main exception and cause
        val exceptionList = properties["\$exception_list"] as List<*>
        assertEquals(1, exceptionList.size)

        // The ThrowableCoercer processes the exception chain with main exception first, then cause
        val exceptions = exceptionList.map { it as Map<*, *> }

        // The first exception should be the main RuntimeException
        val mainException = exceptions[0]
        assertEquals("RuntimeException", mainException["type"])
        assertEquals("Test exception message", mainException["value"])

        assertEquals(thread.id, (mainException["thread_id"] as Number).toLong())

        // Verify mechanism structure for main exception
        val mechanism = mainException["mechanism"] as Map<*, *>
        assertEquals(false, mechanism["handled"])
        assertEquals(false, mechanism["synthetic"])
        assertEquals("UncaughtExceptionHandler", mechanism["type"])

        // Verify stack trace structure for main exception
        val stackTraceMainException = mainException["stacktrace"] as Map<*, *>
        assertEquals("raw", stackTraceMainException["type"])

        val framesMainException = stackTraceMainException["frames"] as List<*>
        assertTrue(framesMainException.isNotEmpty())

        // Verify first frame structure
        val firstFrameMainException = framesMainException.first() as Map<*, *>
        assertTrue(firstFrameMainException.containsKey("module"))
        assertTrue(firstFrameMainException.containsKey("function"))
        assertEquals("java", firstFrameMainException["platform"])
        assertTrue(firstFrameMainException["in_app"] as Boolean)
        assertTrue(firstFrameMainException["lineno"] is Number)

        sut.close()
    }

    @Test
    fun `sets default person properties on SDK setup when enabled`() {
        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseFlagsApi),
            )
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = true, context = TestPostHogContext())

        remoteConfigExecutor.shutdownAndAwaitTermination()

        // Find the flags request
        val flagsRequest =
            (0 until http.requestCount).asSequence()
                .map { http.takeRequest() }
                .firstOrNull { it.path?.contains("/flags/") == true }

        assertNotNull(flagsRequest, "No flags request found")

        val content = flagsRequest.body.unGzip()
        val requestBody = serializer.deserialize<Map<String, Any>>(content.reader())

        // Verify person_properties are present
        val personProperties = requestBody["person_properties"] as? Map<*, *>
        assertNotNull(personProperties, "Person properties not found in request")

        // Verify expected default properties are set
        assertEquals(
            mapOf(
                "\$app_version" to "1.0.0",
                "\$app_build" to "100",
                "\$app_namespace" to "my-namespace",
                "\$os_name" to "Android",
                "\$os_version" to "13",
                "\$device_type" to "Mobile",
                "\$lib" to "posthog-android",
                "\$lib_version" to "1.2.3",
            ),
            personProperties,
        )

        sut.close()
        http.shutdown()
    }

    @Test
    fun `does not set default person properties when disabled`() {
        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseFlagsApi),
            )
        val url = http.url("/")

        // Create config with setDefaultPersonProperties = false
        config =
            PostHogConfig(API_KEY, url.toString()).apply {
                this.storagePrefix = tmpDir.newFolder().absolutePath
                this.setDefaultPersonProperties = false
                this.preloadFeatureFlags = false
                this.context = TestPostHogContext()
            }

        val sut =
            PostHog.withInternal(
                config,
                queueExecutor,
                replayQueueExecutor,
                remoteConfigExecutor,
                cachedEventsExecutor,
                true,
            )

        // Manually trigger flags reload
        sut.reloadFeatureFlags()

        remoteConfigExecutor.shutdownAndAwaitTermination()

        // Find the flags request
        val flagsRequest =
            (0 until http.requestCount).asSequence()
                .map { http.takeRequest() }
                .firstOrNull { it.path?.contains("/flags/") == true }

        assertNotNull(flagsRequest, "No flags request found")

        val content = flagsRequest.body.unGzip()
        val requestBody = serializer.deserialize<Map<String, Any>>(content.reader())

        // Verify person_properties are either absent or empty
        val personProperties = requestBody["person_properties"] as? Map<*, *>
        // Should be null or empty since setDefaultPersonProperties is false
        assertTrue(
            personProperties == null || personProperties.isEmpty(),
            "Person properties should not be set when disabled",
        )

        sut.close()
        http.shutdown()
    }

    @Test
    fun `automatically sets person properties from identify call`() {
        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseFlagsApi),
            )
        val url = http.url("/")

        val sut =
            getSut(url.toString(), preloadFeatureFlags = false, context = TestPostHogContext())

        val userProps =
            mapOf(
                "email" to "user@example.com",
                "plan" to "premium",
                "age" to 30,
            )

        val userPropsOnce =
            mapOf(
                "initial_signup_date" to "2024-01-01",
            )

        // Call identify with user properties
        sut.identify(
            "test_user_123",
            userProperties = userProps,
            userPropertiesSetOnce = userPropsOnce,
        )

        // Reload feature flags to trigger a flags request
        sut.reloadFeatureFlags()

        remoteConfigExecutor.shutdownAndAwaitTermination()

        // Find the flags request
        val flagsRequest =
            (0 until http.requestCount).asSequence()
                .map { http.takeRequest() }
                .firstOrNull { it.path?.contains("/flags/") == true }

        assertNotNull(flagsRequest, "No flags request found")

        val content = flagsRequest.body.unGzip()
        val requestBody = serializer.deserialize<Map<String, Any>>(content.reader())

        // Verify person_properties are present
        val personProperties = requestBody["person_properties"] as? Map<*, *>
        assertNotNull(personProperties, "Person properties not found in request")

        // Verify properties from identify() are included
        assertEquals("user@example.com", personProperties["email"], "email should match")
        assertEquals("premium", personProperties["plan"], "plan should match")
        assertEquals(30, personProperties["age"], "age should match")

        // Verify userPropertiesSetOnce are also included
        assertEquals(
            "2024-01-01",
            personProperties["initial_signup_date"],
            "initial_signup_date should match",
        )

        // Verify default properties are also present (merged with user properties)
        assertNotNull(personProperties["\$app_version"], "app_version should still be present")
        assertNotNull(personProperties["\$os_name"], "os_name should still be present")

        sut.close()
        http.shutdown()
    }

    @Test
    fun `person properties from identify override default properties`() {
        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseFlagsApi),
            )
        val url = http.url("/")

        val sut =
            getSut(url.toString(), preloadFeatureFlags = false, context = TestPostHogContext())

        // Set a property that conflicts with a default property
        val userProps =
            mapOf(
                "\$device_type" to "custom_device",
                "custom_prop" to "custom_value",
            )

        sut.identify("test_user", userProperties = userProps)
        sut.reloadFeatureFlags()

        remoteConfigExecutor.shutdownAndAwaitTermination()

        val flagsRequest =
            (0 until http.requestCount).asSequence()
                .map { http.takeRequest() }
                .firstOrNull { it.path?.contains("/flags/") == true }

        assertNotNull(flagsRequest, "No flags request found")

        val content = flagsRequest.body.unGzip()
        val requestBody = serializer.deserialize<Map<String, Any>>(content.reader())

        val personProperties = requestBody["person_properties"] as? Map<*, *>
        assertNotNull(personProperties, "Person properties not found in request")

        // User-provided property should override the default
        assertEquals(
            "custom_device",
            personProperties["${'$'}device_type"],
            "\$device_type should be overridden by user value",
        )
        assertEquals(
            "custom_value",
            personProperties["custom_prop"],
            "custom_prop should be present",
        )

        // Other default properties should still be present
        assertNotNull(personProperties["\$app_version"], "app_version should still be present")

        sut.close()
        http.shutdown()
    }

    @Test
    fun `setPersonPropertiesForFlags works without person processing`() {
        val http = mockHttp(response = MockResponse().setBody(responseFlagsApi))
        val url = http.url("/")

        val cachePreferences = PostHogMemoryPreferences()
        val sut =
            getSut(
                url.toString(),
                cachePreferences = cachePreferences,
                personProfiles = PersonProfiles.NEVER,
                preloadFeatureFlags = false,
            )

        sut.setPersonPropertiesForFlags(mapOf("email" to "test@example.com", "plan" to "pro"))

        val cachedProps = cachePreferences.getValue(PERSON_PROPERTIES_FOR_FLAGS) as? Map<*, *>
        assertEquals("test@example.com", cachedProps?.get("email"))
        assertEquals("pro", cachedProps?.get("plan"))

        sut.close()
        http.shutdown()
    }

    @Test
    fun `setGroupPropertiesForFlags works without person processing`() {
        val http = mockHttp(response = MockResponse().setBody(responseFlagsApi))
        val url = http.url("/")

        val cachePreferences = PostHogMemoryPreferences()
        val sut =
            getSut(
                url.toString(),
                cachePreferences = cachePreferences,
                personProfiles = PersonProfiles.NEVER,
                preloadFeatureFlags = false,
            )

        sut.setGroupPropertiesForFlags("company", mapOf("name" to "Acme", "plan" to "enterprise"))

        val cachedProps = cachePreferences.getValue(GROUP_PROPERTIES_FOR_FLAGS) as? Map<*, *>
        val companyProps = cachedProps?.get("company") as? Map<*, *>
        assertEquals("Acme", companyProps?.get("name"))
        assertEquals("enterprise", companyProps?.get("plan"))

        sut.close()
        http.shutdown()
    }

    @Test
    fun `setPersonProperties captures set event`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false)

        val userPropertiesToSet = mapOf("email" to "user@example.com", "plan" to "premium")
        val userPropertiesToSetOnce = mapOf("initial_url" to "/blog")

        sut.setPersonProperties(userPropertiesToSet, userPropertiesToSetOnce)

        queueExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()

        assertEquals(1, http.requestCount)
        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        val theEvent = batch.batch.first()
        assertEquals("\$set", theEvent.event)
        assertNotNull(theEvent.distinctId)
        assertEquals(userPropertiesToSet, theEvent.properties!!["\$set"])
        assertEquals(userPropertiesToSetOnce, theEvent.properties!!["\$set_once"])

        sut.close()
    }

    @Test
    fun `setPersonProperties with only userPropertiesToSet`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false)

        val userPropertiesToSet = mapOf("email" to "user@example.com")

        sut.setPersonProperties(userPropertiesToSet)

        queueExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()

        assertEquals(1, http.requestCount)
        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        val theEvent = batch.batch.first()
        assertEquals("\$set", theEvent.event)
        assertEquals(userPropertiesToSet, theEvent.properties!!["\$set"])

        sut.close()
    }

    @Test
    fun `setPersonProperties with only userPropertiesToSetOnce`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false)

        val userPropertiesToSetOnce = mapOf("initial_url" to "/signup")

        sut.setPersonProperties(userPropertiesToSetOnce = userPropertiesToSetOnce)

        queueExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()

        assertEquals(1, http.requestCount)
        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        val theEvent = batch.batch.first()
        assertEquals("\$set", theEvent.event)
        assertEquals(userPropertiesToSetOnce, theEvent.properties!!["\$set_once"])

        sut.close()
    }

    @Test
    fun `setPersonProperties ignores call with empty properties`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false)

        sut.setPersonProperties(emptyMap(), emptyMap())

        queueExecutor.shutdownAndAwaitTermination()

        assertEquals(0, http.requestCount)

        sut.close()
    }

    @Test
    fun `setPersonProperties ignores call with null properties`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false)

        sut.setPersonProperties(null, null)

        queueExecutor.shutdownAndAwaitTermination()

        assertEquals(0, http.requestCount)

        sut.close()
    }

    @Test
    fun `setPersonProperties deduplicates identical calls`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false)

        val userPropertiesToSet = mapOf("email" to "user@example.com")

        // First call should succeed
        sut.setPersonProperties(userPropertiesToSet)

        // Second identical call should be ignored
        sut.setPersonProperties(userPropertiesToSet)

        queueExecutor.shutdownAndAwaitTermination()

        // Only one event should be sent
        assertEquals(1, http.requestCount)

        sut.close()
    }

    @Test
    fun `setPersonProperties allows different properties after reset`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false, reloadFeatureFlags = false)

        val userPropertiesToSet = mapOf("email" to "user@example.com")

        // First call
        sut.setPersonProperties(userPropertiesToSet)

        queueExecutor.awaitExecution()

        assertEquals(1, http.requestCount)

        // Reset clears the cache
        sut.reset()

        // Same properties should now be allowed again
        sut.setPersonProperties(userPropertiesToSet)

        queueExecutor.shutdownAndAwaitTermination()

        // Should have 2 events now
        assertEquals(2, http.requestCount)

        sut.close()
    }

    @Test
    fun `setPersonProperties allows different properties`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false, flushAt = 2)

        val userPropertiesToSet1 = mapOf("email" to "user@example.com")
        val userPropertiesToSet2 = mapOf("email" to "other@example.com")

        sut.setPersonProperties(userPropertiesToSet1)
        sut.setPersonProperties(userPropertiesToSet2)

        queueExecutor.shutdownAndAwaitTermination()

        // Both events should be sent
        assertEquals(1, http.requestCount)

        val request = http.takeRequest()
        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        assertEquals(2, batch.batch.size)

        sut.close()
    }

    @Test
    fun `setPersonProperties is blocked when personProfiles is NEVER`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false, personProfiles = PersonProfiles.NEVER)

        val userPropertiesToSet = mapOf("email" to "user@example.com")

        sut.setPersonProperties(userPropertiesToSet)

        queueExecutor.shutdownAndAwaitTermination()

        assertEquals(0, http.requestCount)

        sut.close()
    }

    @Test
    fun `setPersonProperties updates person properties for flags`() {
        val http = mockHttp(response = MockResponse().setBody(responseFlagsApi))
        val url = http.url("/")

        val cachePreferences = PostHogMemoryPreferences()
        val sut = getSut(url.toString(), preloadFeatureFlags = false, cachePreferences = cachePreferences)

        val userPropertiesToSet = mapOf("email" to "user@example.com", "plan" to "premium")
        val userPropertiesToSetOnce = mapOf("initial_url" to "/blog")

        sut.setPersonProperties(userPropertiesToSet, userPropertiesToSetOnce)

        queueExecutor.shutdownAndAwaitTermination()

        // Verify person properties for flags were updated
        val cachedProps = cachePreferences.getValue(PERSON_PROPERTIES_FOR_FLAGS) as? Map<*, *>
        assertNotNull(cachedProps)
        assertEquals("user@example.com", cachedProps["email"])
        assertEquals("premium", cachedProps["plan"])
        // setOnce properties should be included too
        assertEquals("/blog", cachedProps["initial_url"])

        sut.close()
        http.shutdown()
    }

    @Test
    fun `setPersonProperties deduplicates calls with same properties in different order`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false)

        // Create two maps with the same data but potentially different insertion order
        val userPropertiesToSet1 = linkedMapOf("email" to "user@example.com", "plan" to "premium", "name" to "John")
        val userPropertiesToSet2 = linkedMapOf("plan" to "premium", "name" to "John", "email" to "user@example.com")

        // First call
        sut.setPersonProperties(userPropertiesToSet1)

        // Second call with same data but different key order - should be deduplicated
        sut.setPersonProperties(userPropertiesToSet2)

        queueExecutor.shutdownAndAwaitTermination()

        // Only one event should be sent since the content is the same
        assertEquals(1, http.requestCount)

        sut.close()
    }

    @Test
    fun `setPersonProperties deduplicates calls with same setOnce properties in different order`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false)

        // Create two maps with the same data but potentially different insertion order
        val userPropertiesToSetOnce1 = linkedMapOf("initial_url" to "/blog", "referrer" to "google", "campaign" to "summer")
        val userPropertiesToSetOnce2 = linkedMapOf("campaign" to "summer", "initial_url" to "/blog", "referrer" to "google")

        // First call
        sut.setPersonProperties(userPropertiesToSetOnce = userPropertiesToSetOnce1)

        // Second call with same data but different key order - should be deduplicated
        sut.setPersonProperties(userPropertiesToSetOnce = userPropertiesToSetOnce2)

        queueExecutor.shutdownAndAwaitTermination()

        // Only one event should be sent since the content is the same
        assertEquals(1, http.requestCount)

        sut.close()
    }

    @Test
    fun `setPersonProperties deduplicates calls with nested maps in different order`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false)

        // Create two maps with nested maps in different key order
        val nested1 = linkedMapOf("city" to "London", "country" to "UK", "zip" to "12345")
        val nested2 = linkedMapOf("zip" to "12345", "city" to "London", "country" to "UK")

        val userPropertiesToSet1 = linkedMapOf("name" to "John", "address" to nested1)
        val userPropertiesToSet2 = linkedMapOf("address" to nested2, "name" to "John")

        // First call
        sut.setPersonProperties(userPropertiesToSet1)

        // Second call with same data but different key order at both levels - should be deduplicated
        sut.setPersonProperties(userPropertiesToSet2)

        queueExecutor.shutdownAndAwaitTermination()

        // Only one event should be sent since the content is the same
        assertEquals(1, http.requestCount)

        sut.close()
    }

    @Test
    fun `does not capture duplicate set event if setPersonProperties called with same properties`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false, reloadFeatureFlags = false, flushAt = 1)

        val userPropertiesToSet = mapOf("email" to "user@example.com", "plan" to "premium")
        val userPropertiesToSetOnce = mapOf("initial_url" to "/blog")

        // First call - should capture $set event
        sut.setPersonProperties(userPropertiesToSet, userPropertiesToSetOnce)

        // Second call with same properties - should NOT capture another $set event
        sut.setPersonProperties(userPropertiesToSet, userPropertiesToSetOnce)

        // Third call with same properties - should NOT capture another $set event
        sut.setPersonProperties(userPropertiesToSet, userPropertiesToSetOnce)

        queueExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()

        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        // Should only have 1 event - the duplicate $set events should be ignored
        assertEquals(1, batch.batch.size)
        assertEquals("\$set", batch.batch[0].event)
        assertEquals(userPropertiesToSet, batch.batch[0].properties!!["\$set"])
        assertEquals(userPropertiesToSetOnce, batch.batch[0].properties!!["\$set_once"])

        sut.close()
    }
}

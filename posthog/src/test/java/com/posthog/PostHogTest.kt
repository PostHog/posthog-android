package com.posthog

import com.posthog.internal.PostHogBatchEvent
import com.posthog.internal.PostHogMemoryPreferences
import com.posthog.internal.PostHogPreferences.Companion.GROUPS
import com.posthog.internal.PostHogPrintLogger
import com.posthog.internal.PostHogSendCachedEventsIntegration
import com.posthog.internal.PostHogSerializer
import com.posthog.internal.PostHogThreadFactory
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
    private val featureFlagsExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestFeatureFlags"))
    private val cachedEventsExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestCachedEvents"))
    private val serializer = PostHogSerializer(PostHogConfig(API_KEY))
    private lateinit var config: PostHogConfig

    private val file = File("src/test/resources/json/basic-decide-no-errors.json")
    private val responseDecideApi = file.readText()

    fun getSut(
        host: String,
        flushAt: Int = 1,
        storagePrefix: String = tmpDir.newFolder().absolutePath,
        optOut: Boolean = false,
        preloadFeatureFlags: Boolean = true,
        reloadFeatureFlags: Boolean = true,
        sendFeatureFlagEvent: Boolean = true,
        integration: PostHogIntegration? = null,
        cachePreferences: PostHogMemoryPreferences = PostHogMemoryPreferences(),
        propertiesSanitizer: PostHogPropertiesSanitizer? = null,
    ): PostHogInterface {
        config =
            PostHogConfig(API_KEY, host).apply {
                // for testing
                this.flushAt = flushAt
                this.storagePrefix = storagePrefix
                this.optOut = optOut
                this.preloadFeatureFlags = preloadFeatureFlags
                if (integration != null) {
                    addIntegration(integration)
                }
                this.sendFeatureFlagEvent = sendFeatureFlagEvent
                this.cachePreferences = cachePreferences
                this.propertiesSanitizer = propertiesSanitizer
            }
        return PostHog.withInternal(
            config,
            queueExecutor,
            replayQueueExecutor,
            featureFlagsExecutor,
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

        featureFlagsExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()
        assertEquals(1, http.requestCount)
        assertEquals("/decide/?v=3", request.path)

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

        featureFlagsExecutor.shutdownAndAwaitTermination()

        assertTrue(reloaded)

        sut.close()
    }

    @Test
    fun `isFeatureEnabled returns value after reloaded`() {
        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseDecideApi),
            )
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false)

        sut.reloadFeatureFlags()

        featureFlagsExecutor.shutdownAndAwaitTermination()

        assertTrue(sut.isFeatureEnabled("4535-funnel-bar-viz"))

        sut.close()
    }

    @Test
    fun `getFeatureFlag returns the value after reloaded`() {
        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseDecideApi),
            )
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false)

        sut.reloadFeatureFlags()

        featureFlagsExecutor.shutdownAndAwaitTermination()

        assertTrue(sut.getFeatureFlag("4535-funnel-bar-viz") as Boolean)

        sut.close()
    }

    @Test
    fun `getFeatureFlag captures feature flag event if enabled`() {
        val file = File("src/test/resources/json/basic-decide-with-non-active-flags.json")
        val responseDecideApi = file.readText()

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseDecideApi),
            )
        http.enqueue(
            MockResponse()
                .setBody(""),
        )
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false)

        sut.reloadFeatureFlags()

        featureFlagsExecutor.shutdownAndAwaitTermination()

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
    fun `getFeatureFlagPayload returns the value after reloaded`() {
        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseDecideApi),
            )
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false)

        sut.reloadFeatureFlags()

        featureFlagsExecutor.shutdownAndAwaitTermination()

        assertTrue(sut.getFeatureFlagPayload("thePayload") as Boolean)

        sut.close()
    }

    @Test
    fun `do not preload feature flags if disabled`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false)

        featureFlagsExecutor.shutdownAndAwaitTermination()

        assertEquals(0, http.requestCount)

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
    fun `do not send feature flags called event twice`() {
        val file = File("src/test/resources/json/basic-decide-no-errors.json")
        val responseDecideApi = file.readText()

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseDecideApi),
            )
        http.enqueue(
            MockResponse()
                .setBody(""),
        )
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false)

        sut.reloadFeatureFlags()

        featureFlagsExecutor.shutdownAndAwaitTermination()

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
        val expected = TimeBasedEpochGenerator.getInstance().generate()
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
    fun `does not set session id when reset is called`() {
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

        queueExecutor.awaitExecution()

        var request = http.takeRequest()

        assertEquals(1, http.requestCount)
        var content = request.body.unGzip()
        var batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        var theEvent = batch.batch.first()
        assertNotNull(theEvent.properties!!["\$session_id"])

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
        assertNull(theEvent.properties!!["\$session_id"])

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
}

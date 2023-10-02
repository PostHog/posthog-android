package com.posthog

import com.posthog.internal.PostHogBatchEvent
import com.posthog.internal.PostHogMemoryPreferences
import com.posthog.internal.PostHogSendCachedEventsIntegration
import com.posthog.internal.PostHogSerializer
import com.posthog.internal.PostHogThreadFactory
import okhttp3.mockwebserver.MockResponse
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PostHogTest {

    @get:Rule
    val tmpDir = TemporaryFolder()

    private val queueExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestQueue"))
    private val featureFlagsExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestFeatureFlags"))
    private val cachedEventsExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestCachedEvents"))
    private val serializer = PostHogSerializer(PostHogConfig(apiKey))
    private lateinit var config: PostHogConfig

    fun getSut(
        host: String,
        storagePrefix: String = tmpDir.newFolder().absolutePath,
        optOut: Boolean = false,
        preloadFeatureFlags: Boolean = true,
        reloadFeatureFlags: Boolean = true,
        integration: PostHogIntegration? = null,
    ): PostHogInterface {
        config = PostHogConfig(apiKey, host).apply {
            // for testing
            flushAt = 1
            this.storagePrefix = storagePrefix
            this.optOut = optOut
            this.preloadFeatureFlags = preloadFeatureFlags
            if (integration != null) {
                addIntegration(integration)
            }
        }
        return PostHog.withInternal(config, queueExecutor, featureFlagsExecutor, cachedEventsExecutor, reloadFeatureFlags)
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
        val http = mockHttp(
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
    fun `getFeatureFlagPayload returns the value after reloaded`() {
        val http = mockHttp(
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
            event,
            distinctId,
            props,
            userProperties = userProps,
            userPropertiesSetOnce = userPropsOnce,
            groupProperties = groupProps,
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
            event,
            distinctId,
            props,
            userProperties = userProps,
            userPropertiesSetOnce = userPropsOnce,
            groupProperties = groupProps,
        )

        // do not use extension to not shutdown the executor
        queueExecutor.submit {}.get()

        sut.optIn()

        sut.capture(
            event,
            distinctId,
            props,
            userProperties = userProps,
            userPropertiesSetOnce = userPropsOnce,
            groupProperties = groupProps,
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
            event,
            distinctId,
            props,
            userProperties = userProps,
            userPropertiesSetOnce = userPropsOnce,
            groupProperties = groupProps,
        )

        queueExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()

        assertEquals(1, http.requestCount)
        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        assertEquals(apiKey, batch.apiKey)
        assertNotNull(batch.sentAt)

        val theEvent = batch.batch.first()
        assertEquals(event, theEvent.event)
        assertEquals(distinctId, theEvent.distinctId)
        assertNotNull(theEvent.timestamp)
        assertNotNull(theEvent.uuid)
        assertEquals("value", theEvent.properties!!["prop"] as String)
        assertEquals(userProps, theEvent.properties!!["\$set"])
        assertEquals(userPropsOnce, theEvent.properties!!["\$set_once"])
        assertEquals(groupProps, theEvent.properties!!["\$groups"])

        sut.close()
    }

    @Test
    fun `capture uses generated distinctId if not given`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false)

        sut.capture(
            event,
            properties = props,
            userProperties = userProps,
            userPropertiesSetOnce = userPropsOnce,
            groupProperties = groupProps,
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
            distinctId,
            props,
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
        assertEquals(distinctId, theEvent.distinctId)
        assertEquals("value", theEvent.properties!!["prop"] as String)
        assertNotNull(theEvent.properties!!["\$anon_distinct_id"])
        assertEquals(userProps, theEvent.properties!!["\$set"])
        assertEquals(userPropsOnce, theEvent.properties!!["\$set_once"])

        sut.close()
    }

    @Test
    fun `captures an alias event`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false)

        sut.alias(
            "theAlias",
            props,
        )

        queueExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()

        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        val theEvent = batch.batch.first()
        assertEquals("\$create_alias", theEvent.event)
        assertNotNull(theEvent.distinctId)
        assertEquals("theAlias", theEvent.properties!!["alias"] as String)
        assertEquals("value", theEvent.properties!!["prop"] as String)

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
        // since theres no cached groups yet
        assertNull(theEvent.properties!!["\$groups"])

        sut.close()
    }

    @Test
    fun `registers a property for the next events`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false)

        sut.register("newRegister", true)

        sut.capture(
            event,
            distinctId,
            props,
            userProperties = userProps,
            userPropertiesSetOnce = userPropsOnce,
            groupProperties = groupProps,
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
    fun `unregister removes property`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), preloadFeatureFlags = false)

        sut.register("newRegister", true)

        sut.unregister("newRegister")

        sut.capture(
            event,
            distinctId,
            props,
            userProperties = userProps,
            userPropertiesSetOnce = userPropsOnce,
            groupProperties = groupProps,
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
            event,
            distinctId,
            props,
            userProperties = userProps,
            userPropertiesSetOnce = userPropsOnce,
            groupProperties = groupProps,
        )

        queueExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()

        assertEquals(1, http.requestCount)
        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        assertEquals(apiKey, batch.apiKey)
        assertNotNull(batch.sentAt)

        val theEvent = batch.batch.first()
        assertEquals(event, theEvent.event)
        assertEquals(distinctId, theEvent.distinctId)
        assertNotNull(theEvent.timestamp)
        assertNotNull(theEvent.uuid)
        assertEquals("value", theEvent.properties!!["prop"] as String)
        assertEquals(userProps, theEvent.properties!!["\$set"])
        assertEquals(userPropsOnce, theEvent.properties!!["\$set_once"])
        assertEquals(groupProps, theEvent.properties!!["\$groups"])

        sut.close()
    }
}

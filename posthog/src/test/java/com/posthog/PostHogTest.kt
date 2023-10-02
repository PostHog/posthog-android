package com.posthog

import com.posthog.internal.PostHogBatchEvent
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
    private val serializer = PostHogSerializer(PostHogConfig(apiKey))

    fun getSut(
        host: String = "",
        storagePrefix: String = tmpDir.newFolder().absolutePath,
        optOut: Boolean = false,
        preloadFeatureFlags: Boolean = true,
    ): PostHogInterface {
        val config = PostHogConfig(apiKey, host).apply {
            // for testing
            flushAt = 1
            this.storagePrefix = storagePrefix
            this.optOut = optOut
            this.preloadFeatureFlags = preloadFeatureFlags
        }
        return PostHog.withInternal(config, queueExecutor, featureFlagsExecutor)
    }

    @AfterTest
    fun `set down`() {
        tmpDir.root.deleteRecursively()
    }

    @Test
    fun `optOut is disabled by default`() {
        val sut = getSut()

        assertFalse(sut.isOptOut())

        sut.close()
    }

    @Test
    fun `optOut is enabled if given`() {
        val sut = getSut(optOut = true)

        assertTrue(sut.isOptOut())

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

        val sut = getSut(url.toString(), preloadFeatureFlags = false)

        // identify triggers reloading feature flags because distinctId does not match
        http.enqueue(MockResponse().setBody(responseDecideApi))
        sut.identify(
            distinctId,
            props,
            userProperties = userProps,
            userPropertiesSetOnce = userPropsOnce,
        )

        queueExecutor.shutdownAndAwaitTermination()
        featureFlagsExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()

        // counting also decide api call
        assertEquals(2, http.requestCount)
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
}

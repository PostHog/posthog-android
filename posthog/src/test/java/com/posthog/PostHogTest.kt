package com.posthog

import com.posthog.internal.PostHogBatchEvent
import com.posthog.internal.PostHogSerializer
import com.posthog.internal.PostHogThreadFactory
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class PostHogTest {

    @get:Rule
    val tmpDir = TemporaryFolder()

    private val queueExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("queueExecutor"))
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
        return PostHog.withInternal(config, queueExecutor)
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
    }
}

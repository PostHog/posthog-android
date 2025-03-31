package com.posthog

import com.posthog.internal.PostHogBatchEvent
import com.posthog.internal.PostHogMemoryPreferences
import com.posthog.internal.PostHogSerializer
import com.posthog.internal.PostHogThreadFactory
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

internal class PostHogPersonProfilesTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    private val queueExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestQueue"))
    private val replayQueueExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestReplayQueue"))
    private val remoteConfigExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestRemoteConfig"))
    private val cachedEventsExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestCachedEvents"))
    private val serializer = PostHogSerializer(PostHogConfig(API_KEY))
    private lateinit var config: PostHogConfig

    fun getSut(
        host: String,
        flushAt: Int = 1,
        storagePrefix: String = tmpDir.newFolder().absolutePath,
        cachePreferences: PostHogMemoryPreferences = PostHogMemoryPreferences(),
        personProfiles: PersonProfiles? = PersonProfiles.IDENTIFIED_ONLY,
    ): PostHogInterface {
        config =
            PostHogConfig(API_KEY, host).apply {
                // for testing
                this.flushAt = flushAt
                this.storagePrefix = storagePrefix
                this.preloadFeatureFlags = false
                this.sendFeatureFlagEvent = false
                this.cachePreferences = cachePreferences
                if (personProfiles != null) {
                    this.personProfiles = personProfiles
                }
            }
        return PostHog.withInternal(
            config,
            queueExecutor,
            replayQueueExecutor,
            remoteConfigExecutor,
            cachedEventsExecutor,
            false,
        )
    }

    @AfterTest
    fun `set down`() {
        tmpDir.root.deleteRecursively()
    }

    @Test
    fun `captures an identify event where personProfile is identified_only`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString())

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
        assertEquals(true, theEvent.properties?.get("\$process_person_profile"))
        assertEquals(DISTINCT_ID, theEvent.distinctId)
        assertNotNull(theEvent.properties!!["\$anon_distinct_id"])
        assertEquals(userProps, theEvent.properties!!["\$set"])
        assertEquals(userPropsOnce, theEvent.properties!!["\$set_once"])

        sut.close()
    }

    @Test
    fun `capture sets process person to false if identified only and not identified`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString())

        sut.capture("test event")

        queueExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()

        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        val event = batch.batch.first()

        assertEquals(false, event.properties!!["\$process_person_profile"] as Boolean)

        sut.close()
    }

    @Test
    fun `capture sets process person to true if identified only and with user props`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString())

        sut.capture(
            "test event",
            userProperties = mapOf("userProp" to "value"),
        )

        queueExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()
        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        val event = batch.batch.first()

        assertEquals(true, event.properties!!["\$process_person_profile"] as Boolean)

        sut.close()
    }

    @Test
    fun `capture sets process person to true if identified only and with user set once props`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString())

        sut.capture(
            "test event",
            userPropertiesSetOnce = mapOf("userProp" to "value"),
        )

        queueExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()
        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        val event = batch.batch.first()

        assertEquals(true, event.properties!!["\$process_person_profile"] as Boolean)

        sut.close()
    }

    @Test
    fun `capture sets process person to true if identified only and with group props`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString())

        sut.capture(
            "test event",
            groups = mapOf("groupProp" to "value"),
        )

        queueExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()
        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        val event = batch.batch.first()

        assertEquals(true, event.properties!!["\$process_person_profile"] as Boolean)

        sut.close()
    }

    @Test
    fun `capture sets process person to true if identified only and identified`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), flushAt = 2)

        sut.identify("distinctId")

        sut.capture("test event")

        queueExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()
        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        val event = batch.batch.last()

        assertEquals(true, event.properties!!["\$process_person_profile"] as Boolean)

        sut.close()
    }

    @Test
    fun `capture sets process person to true if identified only and with alias`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), flushAt = 2)

        sut.alias("distinctId")

        sut.capture("test event")

        queueExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()
        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        val event = batch.batch.last()

        assertEquals(true, event.properties!!["\$process_person_profile"] as Boolean)

        sut.close()
    }

    @Test
    fun `capture sets process person to true if identified only and with groups`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), flushAt = 2)

        sut.group("theType", "theKey")

        sut.capture("test event")

        queueExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()
        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        val event = batch.batch.last()

        assertEquals(true, event.properties!!["\$process_person_profile"] as Boolean)

        sut.reset()
        sut.close()
    }

    @Test
    fun `capture sets process person to true if always`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), personProfiles = PersonProfiles.ALWAYS)

        sut.capture("test event")

        queueExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()
        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        val event = batch.batch.first()

        assertEquals(true, event.properties!!["\$process_person_profile"] as Boolean)

        sut.reset()
        sut.close()
    }

    @Test
    fun `capture sets process person to false if never and identify called`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), personProfiles = PersonProfiles.NEVER)

        sut.identify("distinctId")

        sut.capture("test event")

        queueExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()
        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        val event = batch.batch.first()

        assertEquals(false, event.properties!!["\$process_person_profile"] as Boolean)

        sut.reset()
        sut.close()
    }

    @Test
    fun `capture sets process person to false if never and alias called`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), personProfiles = PersonProfiles.NEVER)

        sut.alias("distinctId")

        sut.capture("test event")

        queueExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()
        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        val event = batch.batch.first()

        assertEquals(false, event.properties!!["\$process_person_profile"] as Boolean)

        sut.reset()
        sut.close()
    }

    @Test
    fun `capture sets process person to false if never and group called`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), personProfiles = PersonProfiles.NEVER)

        sut.group("theType", "theKey")

        sut.capture("test event")

        queueExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()
        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        val event = batch.batch.first()

        assertEquals(false, event.properties!!["\$process_person_profile"] as Boolean)

        sut.reset()
        sut.close()
    }
}

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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

internal class PostHogPersonProfilesTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    private val queueExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestQueue"))
    private val replayQueueExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestReplayQueue"))
    private val remoteConfigExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestRemoteConfig"))
    private val cachedEventsExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestCachedEvents"))
    private val serializer = PostHogSerializer(PostHogConfig(PROJECT_TOKEN))
    private lateinit var config: PostHogConfig

    fun getSut(
        host: String,
        flushAt: Int = 1,
        storagePrefix: String = tmpDir.newFolder().absolutePath,
        cachePreferences: PostHogMemoryPreferences = PostHogMemoryPreferences(),
        personProfiles: PersonProfiles? = PersonProfiles.IDENTIFIED_ONLY,
    ): PostHogInterface {
        config =
            PostHogConfig(PROJECT_TOKEN, host).apply {
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

    private val PostHogEvent.processPersonProfile: Any?
        get() = options?.get("process_person_profile")

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
        assertNull(theEvent.processPersonProfile)
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

        assertEquals(false, event.processPersonProfile)

        sut.close()
    }

    @Test
    fun `caller person processing properties don't override the personless option if identified only and not identified`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString())

        sut.capture(
            "test event",
            properties = mapOf("\$process_person_profile" to true, "\$is_identified" to true),
        )

        queueExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()
        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        val event = batch.batch.first()

        // the SDK's personless option wins; the caller's legacy property is removed when sent
        assertEquals(false, event.processPersonProfile)
        assertFalse(event.properties!!.containsKey("\$process_person_profile"))
        assertEquals(true, event.properties!!["\$is_identified"] as Boolean)

        sut.close()
        http.shutdown()
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

        assertNull(event.processPersonProfile)

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

        assertNull(event.processPersonProfile)

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

        assertNull(event.processPersonProfile)

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

        assertNull(event.processPersonProfile)

        sut.close()
    }

    @Test
    fun `caller person processing properties win if identified only and identified`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), flushAt = 2)

        sut.identify("distinctId")

        sut.capture(
            "test event",
            properties = mapOf("\$process_person_profile" to false, "\$is_identified" to false),
        )

        queueExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()
        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        val event = batch.batch.last()

        assertEquals(false, event.processPersonProfile)
        assertFalse(event.properties!!.containsKey("\$process_person_profile"))
        assertEquals(false, event.properties!!["\$is_identified"] as Boolean)

        sut.close()
        http.shutdown()
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

        assertNull(event.processPersonProfile)

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

        assertNull(event.processPersonProfile)

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

        assertNull(event.processPersonProfile)

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

        assertEquals(false, event.processPersonProfile)

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

        assertEquals(false, event.processPersonProfile)

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

        assertEquals(false, event.processPersonProfile)

        sut.reset()
        sut.close()
    }

    @Test
    fun `caller process person property doesn't override never`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(url.toString(), personProfiles = PersonProfiles.NEVER)

        sut.capture("test event", properties = mapOf("\$process_person_profile" to true))

        queueExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()
        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        val event = batch.batch.first()

        assertEquals(false, event.processPersonProfile)
        assertFalse(event.properties!!.containsKey("\$process_person_profile"))

        sut.close()
        http.shutdown()
    }
}

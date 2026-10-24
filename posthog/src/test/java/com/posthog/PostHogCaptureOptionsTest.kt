package com.posthog

import com.posthog.internal.PostHogBatchEvent
import com.posthog.internal.PostHogMemoryPreferences
import com.posthog.internal.PostHogSerializer
import com.posthog.internal.PostHogThreadFactory
import okhttp3.mockwebserver.MockWebServer
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PostHogCaptureOptionsTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    @get:Rule
    val httpServers = TestHttpServers()

    private val queueExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestQueue"))
    private val replayQueueExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestReplayQueue"))
    private val remoteConfigExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestRemoteConfig"))
    private val cachedEventsExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestCachedEvents"))
    private val serializer = PostHogSerializer(PostHogConfig(PROJECT_TOKEN))
    private lateinit var config: PostHogConfig

    // Events as beforeSend sees them. The hook drops them, so each capture is recorded once and
    // nothing is sent.
    private val captured = mutableListOf<PostHogEvent>()

    private fun getSut(
        host: String = "http://localhost",
        personProfiles: PersonProfiles = PersonProfiles.IDENTIFIED_ONLY,
        beforeSend: PostHogBeforeSend =
            PostHogBeforeSend { event ->
                captured.add(event)
                null
            },
    ): PostHogInterface {
        val storage = tmpDir.newFolder().absolutePath
        config =
            PostHogConfig(PROJECT_TOKEN, host).apply {
                flushAt = 1
                storagePrefix = File(storage, "events").absolutePath
                replayStoragePrefix = File(storage, "snapshots").absolutePath
                preloadFeatureFlags = false
                sendFeatureFlagEvent = false
                cachePreferences = PostHogMemoryPreferences()
                context = TestPostHogContext()
                this.personProfiles = personProfiles
                addBeforeSend(beforeSend)
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

    private fun sentEvents(http: MockWebServer): List<PostHogEvent> =
        List(http.requestCount) {
            serializer.deserialize<PostHogBatchEvent>(http.takeRequest().body.unGzip().reader()).batch
        }.flatten()

    private fun PostHogEvent.processPersonProfile(): Any? = options?.get("process_person_profile")

    @Test
    fun `options reach the event of every event-sending method`() {
        val options = mapOf("cookieless_mode" to true)
        val cases =
            listOf<Pair<String, (PostHogInterface) -> Unit>>(
                "test_event" to { it.capture("test_event", options = options) },
                PostHogEventName.SCREEN.event to { it.screen("screen", options = options) },
                PostHogEventName.IDENTIFY.event to { it.identify("user_id", options = options) },
                PostHogEventName.SET.event to { it.setPersonProperties(userProps, options = options) },
                PostHogEventName.CREATE_ALIAS.event to { it.alias("alias", options = options) },
                PostHogEventName.GROUP_IDENTIFY.event to { it.group("type", "key", options = options) },
                PostHogEventName.EXCEPTION.event to { it.captureException(RuntimeException("boom"), options = options) },
            )
        for ((eventName, trigger) in cases) {
            captured.clear()
            // always processed, so the SDK adds no option of its own
            val sut = getSut(personProfiles = PersonProfiles.ALWAYS)

            trigger(sut)

            val event = captured.single { it.event == eventName }
            assertEquals<Map<String, Any?>?>(mapOf("cookieless_mode" to true), event.options, eventName)
            sut.close()
        }
    }

    @Test
    fun `identify with the same id sends its options on the set event`() {
        val sut = getSut()
        sut.identify("user_id")

        sut.identify("user_id", userProperties = userProps, options = mapOf("cookieless_mode" to true))

        val event = captured.single { it.event == PostHogEventName.SET.event }
        assertEquals<Map<String, Any?>?>(mapOf("cookieless_mode" to true), event.options)
        sut.close()
    }

    @Test
    fun `the event gets a copy of the caller's options`() {
        val callerOptions = mutableMapOf<String, Any?>("cookieless_mode" to true)
        val sut = getSut()

        sut.capture("test_event", options = callerOptions)

        val event = captured.single()
        assertNotSame(callerOptions, event.options)
        event.options!!["product_tour_id"] = "tour"
        assertEquals<Map<String, Any?>?>(mapOf("cookieless_mode" to true), callerOptions)
        sut.close()
    }

    @Test
    fun `beforeSend can edit the options`() {
        val http = httpServers.mockHttp()
        val sut =
            getSut(
                http.url("/").toString(),
                beforeSend =
                    PostHogBeforeSend { event ->
                        event.options = (event.options ?: mutableMapOf()).apply { put("cookieless_mode", true) }
                        event
                    },
            )

        sut.capture("test_event", options = mapOf("product_tour_id" to "tour"))
        queueExecutor.shutdownAndAwaitTermination()

        val event = sentEvents(http).single()
        assertEquals("tour", event.options?.get("product_tour_id"))
        assertEquals(true, event.options?.get("cookieless_mode"))
        sut.close()
    }

    @Test
    fun `beforeSend can remove the personless option and the send does not add it back`() {
        val http = httpServers.mockHttp()
        val sut =
            getSut(
                http.url("/").toString(),
                personProfiles = PersonProfiles.NEVER,
                beforeSend =
                    PostHogBeforeSend { event ->
                        event.options = null
                        event
                    },
            )

        sut.capture("test_event")
        queueExecutor.shutdownAndAwaitTermination()

        val event = sentEvents(http).single()
        assertNull(event.options)
        assertFalse(event.properties!!.containsKey("\$process_person_profile"))
        sut.close()
    }

    @Test
    fun `the personless option is set only when person processing is off`() {
        val never = getSut(personProfiles = PersonProfiles.NEVER)
        never.capture("test_event")
        assertEquals(false, captured.single().processPersonProfile())
        assertFalse(captured.single().properties!!.containsKey("\$process_person_profile"))
        never.close()

        captured.clear()
        val identifiedOnly = getSut(personProfiles = PersonProfiles.IDENTIFIED_ONLY)
        identifiedOnly.capture("anonymous_event")
        identifiedOnly.identify("user_id")
        identifiedOnly.capture("identified_event")
        assertEquals(false, captured.single { it.event == "anonymous_event" }.processPersonProfile())
        assertNull(captured.single { it.event == PostHogEventName.IDENTIFY.event }.options)
        assertNull(captured.single { it.event == "identified_event" }.options)
        identifiedOnly.close()

        captured.clear()
        val always = getSut(personProfiles = PersonProfiles.ALWAYS)
        always.capture("test_event")
        assertNull(captured.single().options)
        always.close()
    }

    @Test
    fun `a caller option wins over the personless default`() {
        val sut = getSut(personProfiles = PersonProfiles.NEVER)

        sut.capture("explicit", options = mapOf("process_person_profile" to true))
        sut.capture("unset", options = mapOf("process_person_profile" to null, "cookieless_mode" to true))

        assertEquals<Map<String, Any?>?>(mapOf("process_person_profile" to true), captured.single { it.event == "explicit" }.options)
        assertEquals<Map<String, Any?>?>(
            mapOf<String, Any?>("process_person_profile" to false, "cookieless_mode" to true),
            captured.single { it.event == "unset" }.options,
        )
        sut.close()
    }

    @Test
    fun `a registered legacy person processing property suppresses the personless option`() {
        val sut = getSut(personProfiles = PersonProfiles.NEVER)
        sut.register("\$process_person_profile", true)

        sut.capture("test_event")

        val event = captured.single()
        assertNull(event.options)
        assertEquals(true, event.properties!!["\$process_person_profile"])
        sut.close()
    }

    @Test
    fun `registered properties win over SDK properties, caller properties win over registered ones`() {
        val sut = getSut()
        sut.register("\$os_name", "registered")
        sut.register("\$is_identified", true)
        sut.register("custom", "registered")

        sut.capture("test_event", properties = mapOf("custom" to "caller"))

        val props = captured.single().properties!!
        assertEquals("registered", props["\$os_name"])
        assertEquals(true, props["\$is_identified"])
        assertEquals("caller", props["custom"])
        sut.close()
    }

    @Test
    fun `beforeSend wins over caller properties`() {
        val http = httpServers.mockHttp()
        val sut =
            getSut(
                http.url("/").toString(),
                beforeSend =
                    PostHogBeforeSend { event ->
                        event.properties?.set("custom", "hook")
                        event
                    },
            )

        sut.capture("test_event", properties = mapOf("custom" to "caller"))
        queueExecutor.shutdownAndAwaitTermination()

        assertEquals("hook", sentEvents(http).single().properties!!["custom"])
        sut.close()
    }

    @Test
    fun `typed call values win over registered set and groups properties`() {
        val sut = getSut()
        sut.register("\$set", mapOf("plan" to "registered"))
        sut.register("\$groups", mapOf("company" to "registered"))

        sut.capture("test_event", userProperties = mapOf("plan" to "call"), groups = mapOf("company" to "call"))

        val props = captured.single().properties!!
        assertEquals(mapOf("plan" to "call"), props["\$set"] as Map<*, *>)
        assertEquals(mapOf("company" to "call"), props["\$groups"] as Map<*, *>)
        sut.close()
    }

    @Test
    fun `a blank distinct id falls back to the current one`() {
        val sut = getSut()

        sut.capture("test_event", distinctId = "  ")
        sut.identify("  ")

        assertEquals(sut.distinctId(), captured.single().distinctId)
        assertTrue(captured.none { it.event == PostHogEventName.IDENTIFY.event })
        sut.close()
    }

    @Test
    fun `a personless set neither stores nor matches the person properties hash`() {
        val personless = mapOf("process_person_profile" to false)
        val sut = getSut()
        sut.identify("user_id")
        captured.clear()

        sut.setPersonProperties(userProps, options = personless)
        sut.setPersonProperties(userProps)
        sut.setPersonProperties(userProps, options = personless)
        sut.setPersonProperties(userProps)

        // only the second processed call is a duplicate
        assertEquals(3, captured.count { it.event == PostHogEventName.SET.event })
        sut.close()
    }

    @Test
    fun `an event returned by beforeSend keeps the uuid of its own capture`() {
        val http = httpServers.mockHttp(total = 2)
        var cached: PostHogEvent? = null
        val sut =
            getSut(
                http.url("/").toString(),
                beforeSend =
                    PostHogBeforeSend { event ->
                        cached ?: event.also { cached = it }
                    },
            )

        sut.capture("first")
        sut.capture("second")
        queueExecutor.shutdownAndAwaitTermination()

        val uuids = sentEvents(http).map { it.uuid }
        assertEquals(2, uuids.size)
        assertNotEquals(uuids[0], uuids[1])
        sut.close()
    }
}

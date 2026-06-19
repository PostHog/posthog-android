package com.posthog.server

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PostHogRequestContextTest {
    @Test
    fun `captures use request context identity session and properties`() {
        withPostHog { http, postHog ->
            val context =
                PostHogRequestContext.fromHeaders(
                    headers =
                        mapOf(
                            PostHogRequestContext.DISTINCT_ID_HEADER to "frontend-user",
                            PostHogRequestContext.SESSION_ID_HEADER to "frontend-session",
                        ),
                    properties =
                        mapOf(
                            "\$current_url" to "https://example.com/api/test",
                            "\$request_method" to "POST",
                            "\$request_path" to "/api/test",
                            "\$user_agent" to "TestAgent/1.0",
                            "\$ip" to "10.0.0.2",
                        ),
                )

            PostHogRequestContext.beginScope(context, fresh = true).use {
                postHog.capture("request-event")
            }

            val request = http.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(request)
            val batch = request.parseBatch()
            val event = batch.firstEvent
            assertEquals("frontend-user", event?.get("distinct_id")?.asString)
            val properties = batch.firstEventProperties()
            assertEquals("frontend-session", properties["\$session_id"])
            assertEquals("https://example.com/api/test", properties["\$current_url"])
            assertEquals("POST", properties["\$request_method"])
            assertEquals("/api/test", properties["\$request_path"])
            assertEquals("TestAgent/1.0", properties["\$user_agent"])
            assertEquals("10.0.0.2", properties["\$ip"])
            assertFalse(properties.containsKey("\$process_person_profile"))
        }
    }

    @Test
    fun `null capture distinct id uses request context identity`() {
        withPostHog { http, postHog ->
            PostHogRequestContext.beginScope(PostHogRequestContextData(distinctId = "context-user"), fresh = true).use {
                postHog.capture(null, "null-distinct-event")
            }

            val request = http.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(request)
            val batch = request.parseBatch()
            assertEquals("context-user", batch.firstEvent?.get("distinct_id")?.asString)
            assertFalse(batch.firstEventProperties().containsKey("\$process_person_profile"))
        }
    }

    @Test
    fun `explicit capture values override request context`() {
        withPostHog { http, postHog ->
            PostHogRequestContext.beginScope(
                PostHogRequestContextData(
                    distinctId = "context-user",
                    sessionId = "context-session",
                    properties = mapOf("shared" to "context-value", "context-only" to "context-only-value"),
                ),
                fresh = true,
            ).use {
                postHog.capture(
                    distinctId = "explicit-user",
                    event = "explicit-event",
                    properties = mapOf("shared" to "explicit-value", "\$session_id" to "explicit-session"),
                )
            }

            val request = http.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(request)
            val batch = request.parseBatch()
            assertEquals("explicit-user", batch.firstEvent?.get("distinct_id")?.asString)
            val properties = batch.firstEventProperties()
            assertEquals("explicit-session", properties["\$session_id"])
            assertEquals("explicit-value", properties["shared"])
            assertEquals("context-only-value", properties["context-only"])
        }
    }

    @Test
    fun `nested child context session override updates emitted session property`() {
        withPostHog { http, postHog ->
            PostHogRequestContext.beginScope(
                PostHogRequestContextData(
                    distinctId = "outer-user",
                    sessionId = "outer-session",
                    properties = mapOf("outer" to true),
                ),
                fresh = true,
            ).use {
                PostHogRequestContext.beginScope(
                    PostHogRequestContextData(
                        sessionId = "child-session",
                        properties = mapOf("inner" to true),
                    ),
                ).use {
                    postHog.capture("nested-session-event")
                }
            }

            val request = http.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(request)
            val batch = request.parseBatch()
            assertEquals("outer-user", batch.firstEvent?.get("distinct_id")?.asString)
            val properties = batch.firstEventProperties()
            assertEquals("child-session", properties["\$session_id"])
            assertEquals(true, properties["outer"])
            assertEquals(true, properties["inner"])
        }
    }

    @Test
    fun `tracing headers can be disabled while preserving request metadata`() {
        withPostHog { http, postHog ->
            val context =
                PostHogRequestContext.fromHeaders(
                    headers =
                        mapOf(
                            PostHogRequestContext.DISTINCT_ID_HEADER to "header-user",
                            PostHogRequestContext.SESSION_ID_HEADER to "header-session",
                        ),
                    captureTracingHeaders = false,
                    properties = mapOf("\$request_path" to "/api/test"),
                )

            PostHogRequestContext.beginScope(context, fresh = true).use {
                postHog.capture("metadata-event")
            }

            val request = http.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(request)
            val batch = request.parseBatch()
            val distinctId = batch.firstEvent?.get("distinct_id")?.asString
            assertNotNull(distinctId)
            assertNotEquals("header-user", distinctId)
            val parsedDistinctId = java.util.UUID.fromString(distinctId)
            assertEquals(7, parsedDistinctId.version())
            val properties = batch.firstEventProperties()
            assertEquals(false, properties["\$process_person_profile"])
            assertFalse(properties.containsKey("\$session_id"))
            assertEquals("/api/test", properties["\$request_path"])
        }
    }

    @Test
    fun `missing identity creates personless event without session`() {
        withPostHog { http, postHog ->
            PostHogRequestContext.beginScope(PostHogRequestContextData(properties = mapOf("request" to "present")), fresh = true)
                .use {
                    postHog.capture("personless-event")
                }

            val request = http.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(request)
            val batch = request.parseBatch()
            val distinctId = batch.firstEvent?.get("distinct_id")?.asString
            assertNotNull(distinctId)
            val parsedDistinctId = java.util.UUID.fromString(distinctId)
            assertEquals(7, parsedDistinctId.version())
            val properties = batch.firstEventProperties()
            assertEquals(false, properties["\$process_person_profile"])
            assertEquals("present", properties["request"])
            assertFalse(properties.containsKey("\$session_id"))
        }
    }

    @Test
    fun `explicit process person profile property is preserved for personless events`() {
        withPostHog { http, postHog ->
            postHog.capture("", "personless-event", properties = mapOf("\$process_person_profile" to true))

            val request = http.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(request)
            val batch = request.parseBatch()
            val properties = batch.firstEventProperties()
            assertEquals(true, properties["\$process_person_profile"])
        }
    }

    @Test
    fun `exception capture falls back to request context and explicit distinct id wins`() {
        withPostHog(flushAt = 2) { http, postHog ->
            PostHogRequestContext.beginScope(
                PostHogRequestContextData(
                    distinctId = "context-user",
                    sessionId = "context-session",
                    properties = mapOf("\$request_path" to "/api/test"),
                ),
                fresh = true,
            ).use {
                postHog.captureException(IllegalStateException("boom"))
                postHog.captureException(IllegalArgumentException("boom 2"), "explicit-user")
            }

            val request = http.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(request)
            val batch = request.parseBatch()
            val exceptions = batch.batch.filter { it.get("event")?.asString == "\$exception" }
            assertEquals(2, exceptions.size)
            assertEquals("context-user", exceptions[0].get("distinct_id")?.asString)
            assertEquals("context-session", exceptions[0].getAsJsonObject("properties").get("\$session_id")?.asString)
            assertEquals("/api/test", exceptions[0].getAsJsonObject("properties").get("\$request_path")?.asString)
            assertEquals("explicit-user", exceptions[1].get("distinct_id")?.asString)
            assertEquals("context-session", exceptions[1].getAsJsonObject("properties").get("\$session_id")?.asString)
        }
    }

    @Test
    fun `concurrent request contexts do not leak between threads`() {
        val executor = Executors.newFixedThreadPool(2)
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val results = Collections.synchronizedMap(mutableMapOf<String, Pair<String?, String?>>())

        fun submit(
            key: String,
            distinctId: String,
            sessionId: String,
        ) {
            executor.submit {
                PostHogRequestContext.beginScope(PostHogRequestContextData(distinctId, sessionId), fresh = true).use {
                    started.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    val current = PostHogRequestContext.current()
                    results[key] = current?.distinctId to current?.sessionId
                }
            }
        }

        try {
            submit("first", "user-a", "session-a")
            submit("second", "user-b", "session-b")
            assertTrue(started.await(5, TimeUnit.SECONDS))
            release.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))

            assertEquals("user-a" to "session-a", results["first"])
            assertEquals("user-b" to "session-b", results["second"])
            assertNull(PostHogRequestContext.current())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `headers are case insensitive and sanitized safely`() {
        val context =
            PostHogRequestContext.fromHeaders(
                mapOf(
                    "x-posthog-distinct-id" to " \u0000\u0001 ",
                    "x-posthog-session-id" to listOf("  ${"s".repeat(1200)}\u0000  "),
                ),
            )

        assertNull(context.distinctId)
        assertEquals("s".repeat(1000), context.sessionId)
        assertEquals("s".repeat(1000), context.properties?.get("\$session_id"))
    }

    @Test
    fun `nested contexts inherit unless fresh`() {
        PostHogRequestContext.beginScope(
            PostHogRequestContextData("outer-user", "outer-session", mapOf("outer" to true)),
            fresh = true,
        ).use {
            PostHogRequestContext.beginScope(PostHogRequestContextData(properties = mapOf("inner" to true))).use {
                val current = PostHogRequestContext.current()
                assertEquals("outer-user", current?.distinctId)
                assertEquals("outer-session", current?.sessionId)
                assertEquals(true, current?.properties?.get("outer"))
                assertEquals(true, current?.properties?.get("inner"))
            }

            PostHogRequestContext.beginScope(PostHogRequestContextData(properties = mapOf("fresh" to true)), fresh = true).use {
                val current = PostHogRequestContext.current()
                assertNull(current?.distinctId)
                assertNull(current?.sessionId)
                assertFalse(current?.properties?.containsKey("outer") ?: true)
                assertEquals(true, current?.properties?.get("fresh"))
            }
        }

        assertNull(PostHogRequestContext.current())
    }

    private inline fun withPostHog(
        flushAt: Int = 1,
        block: (MockWebServer, PostHogInterface) -> Unit,
    ) {
        val http = MockWebServer()
        var postHog: PostHogInterface? = null
        try {
            http.enqueue(MockResponse().setResponseCode(200))
            http.start()
            postHog = createPostHog(http, flushAt)
            block(http, postHog)
        } finally {
            postHog?.close()
            runCatching { http.shutdown() }
        }
    }

    private fun createPostHog(
        http: MockWebServer,
        flushAt: Int = 1,
    ): PostHogInterface =
        PostHog.with(
            PostHogConfig.builder(TEST_API_KEY)
                .host(http.url("/").toString())
                .flushAt(flushAt)
                .build(),
        )
}

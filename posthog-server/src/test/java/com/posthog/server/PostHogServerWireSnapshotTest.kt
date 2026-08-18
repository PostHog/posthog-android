package com.posthog.server

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.posthog.PostHogBeforeSend
import com.posthog.internal.PostHogDateProvider
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Date
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class PostHogServerWireSnapshotTest {
    @Test
    fun `batch snapshot covers capture identify alias and group identify`() {
        withServer { server ->
            withClient(createClient(server, flushAt = 4)) { postHog ->
                postHog.capture(
                    distinctId = "user-123",
                    event = "invoice paid",
                    properties = linkedMapOf("amount" to 12.5, "currency" to "USD"),
                    userProperties = linkedMapOf("name" to "Ada", "plan" to "pro"),
                    userPropertiesSetOnce = linkedMapOf("initial_referrer" to "docs"),
                    groups = linkedMapOf("company" to "posthog"),
                )
                postHog.identify(
                    "user-123",
                    userProperties = linkedMapOf("email" to "ada@example.com", "roles" to listOf("admin", "author")),
                    userPropertiesSetOnce = linkedMapOf("created_by" to "snapshot"),
                )
                postHog.alias("user-123", "legacy-user-456")
                postHog.group(
                    "user-123",
                    "company",
                    "posthog",
                    linkedMapOf("name" to "PostHog", "employees" to 100),
                )

                assertBatchSnapshot(server.takeRequiredRequest(), "server-batch-family.json")
            }
        }
    }

    @Test
    fun `batch snapshot covers null empty and nested safe properties`() {
        withServer { server ->
            withClient(createClient(server, flushAt = 1)) { postHog ->
                val nullableProperties =
                    linkedMapOf<String, Any?>(
                        "null_value" to null,
                        "empty_string" to "",
                        "empty_object" to emptyMap<String, Any?>(),
                        "empty_array" to emptyList<Any?>(),
                        "nested" to
                            linkedMapOf(
                                "kept" to "value",
                                "dropped_null" to null,
                                "deeper" to linkedMapOf("count" to 2, "dropped_nan" to Double.NaN),
                            ),
                        "ordered_list" to listOf("first", null, "third", Double.NaN),
                        "ordered_array" to arrayOf("alpha", null, "omega"),
                    )

                @Suppress("UNCHECKED_CAST")
                postHog.capture("safe-user", "safe properties", nullableProperties as Map<String, Any>)

                assertBatchSnapshot(server.takeRequiredRequest(), "server-safe-properties.json")
            }
        }
    }

    @Test
    fun `batch snapshot covers a complete deterministic exception event`() {
        withServer { server ->
            withClient(createClient(server, flushAt = 1, releaseIdentifier = "release-2025.01")) { postHog ->
                val projectRoot = File(".").canonicalFile.path
                val cause = IllegalStateException("database offline")
                cause.stackTrace =
                    arrayOf(
                        StackTraceElement(
                            "com.example.Database",
                            "execute",
                            "$projectRoot/posthog-server/src/test/fixtures/Database.kt",
                            73,
                        ),
                    )
                val exception = RuntimeException("checkout failed", cause)
                exception.stackTrace =
                    arrayOf(
                        StackTraceElement(
                            "com.example.CheckoutService",
                            "submit",
                            "$projectRoot/posthog-server/src/test/fixtures/CheckoutService.kt",
                            42,
                        ),
                        StackTraceElement("com.example.ApiHandler", "handle", "ApiHandler.kt", 18),
                    )

                postHog.captureException(
                    exception,
                    distinctId = "user-123",
                    properties = linkedMapOf("request_id" to "request-456", "tags" to listOf("checkout", "backend")),
                )

                val body = server.takeRequiredRequest().decodedJsonBody()
                normalizeExceptionVolatiles(body)
                assertJsonSnapshot(body, "server-exception.json")
            }
        }
    }

    @Test
    fun `flags request snapshots cover minimal and maximal payloads`() {
        synchronized(TimeZone::class.java) {
            val originalTimeZone = TimeZone.getDefault()
            try {
                TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

                withServer { server ->
                    withClient(createClient(server)) { postHog ->
                        postHog.evaluateFlags("minimal-user")

                        val request = server.takeRequiredRequest()
                        assertEquals("/flags/?v=2", request.path)
                        assertJsonSnapshot(request.decodedJsonBody(), "server-flags-minimal.json")
                    }
                }

                withServer { server ->
                    withClient(
                        createClient(
                            server,
                            evaluationContexts = listOf("production", "checkout"),
                        ),
                    ) { postHog ->
                        postHog.evaluateFlags(
                            distinctId = "maximal-user",
                            groups = linkedMapOf("company" to "posthog", "project" to "android"),
                            personProperties =
                                linkedMapOf(
                                    "email" to "ada@example.com",
                                    "age" to 37,
                                    "traits" to linkedMapOf("role" to "admin", "active" to true),
                                ),
                            groupProperties =
                                linkedMapOf(
                                    "company" to linkedMapOf("employees" to 100, "region" to "EU"),
                                    "project" to linkedMapOf("tier" to "open-source"),
                                ),
                            flagKeys = listOf("checkout-v2", "new-pricing"),
                            disableGeoip = true,
                        )

                        val request = server.takeRequiredRequest()
                        assertEquals("/flags/?v=2", request.path)
                        assertJsonSnapshot(request.decodedJsonBody(), "server-flags-maximal.json")
                    }
                }
            } finally {
                TimeZone.setDefault(originalTimeZone)
            }
        }
    }

    private fun createClient(
        server: MockWebServer,
        flushAt: Int = 100,
        releaseIdentifier: String? = null,
        evaluationContexts: List<String>? = null,
    ): PostHog {
        server.enqueue(jsonResponse("{\"flags\":{}}"))

        val config =
            PostHogConfig.builder(TEST_API_KEY)
                .host(server.url("/").toString())
                .flushAt(flushAt)
                .flushIntervalSeconds(3600)
                .evaluationContexts(evaluationContexts)
                .releaseIdentifier(releaseIdentifier)
                .build()
        config.addBeforeSend(
            PostHogBeforeSend { event ->
                event.copy(
                    timestamp = date,
                    uuid = eventUuid(event.event),
                )
            },
        )

        return PostHog().apply {
            setup(config)
            setFixedDateProvider()
        }
    }

    private fun PostHog.setFixedDateProvider() {
        val configField = com.posthog.PostHogStateless::class.java.getDeclaredField("config")
        configField.isAccessible = true
        val coreConfig = configField.get(this) as com.posthog.PostHogConfig
        coreConfig.dateProvider = FixedDateProvider(date)
    }

    private fun eventUuid(event: String): UUID = UUID.nameUUIDFromBytes("$uuid:$event".toByteArray(StandardCharsets.UTF_8))

    private fun assertBatchSnapshot(
        request: RecordedRequest,
        fixtureName: String,
    ) {
        assertEquals("/batch", request.path)
        assertJsonSnapshot(request.decodedJsonBody(), fixtureName)
    }

    private fun RecordedRequest.decodedJsonBody(): JsonElement = JsonParser.parseString(body.unGzip())

    private fun MockWebServer.takeRequiredRequest(): RecordedRequest {
        val request = takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(request, "Expected request within 5 seconds")
        return request
    }

    private fun assertJsonSnapshot(
        actual: JsonElement,
        fixtureName: String,
    ) {
        val resource = javaClass.classLoader.getResource("json/$fixtureName")
        assertNotNull(resource, "Missing snapshot fixture json/$fixtureName")
        val expected = JsonParser.parseString(resource.readText())
        val canonicalExpected = canonicalize(expected)
        val canonicalActual = canonicalize(actual)
        assertEquals(
            PRETTY_GSON.toJson(canonicalExpected),
            PRETTY_GSON.toJson(canonicalActual),
            "Wire JSON did not match json/$fixtureName",
        )
    }

    private fun canonicalize(element: JsonElement): JsonElement =
        when {
            element.isJsonObject ->
                JsonObject().apply {
                    element.asJsonObject.entrySet().sortedBy { it.key }.forEach { (key, value) ->
                        add(key, canonicalize(value))
                    }
                }
            element.isJsonArray ->
                JsonArray().apply {
                    element.asJsonArray.forEach { add(canonicalize(it)) }
                }
            else -> element.deepCopy()
        }

    private fun normalizeExceptionVolatiles(body: JsonElement) {
        val events = body.asJsonObject.getAsJsonArray("batch") ?: return
        events.forEach { event ->
            if (event.asJsonObject.get("event")?.asString != "\$exception") return@forEach
            val exceptionList =
                event.asJsonObject
                    .getAsJsonObject("properties")
                    .getAsJsonArray("\$exception_list") ?: return@forEach
            exceptionList.forEach { exception ->
                val exceptionObject = exception.asJsonObject
                val threadId = exceptionObject.get("thread_id")
                assertNotNull(threadId, "Expected exception thread_id")
                assertTrue(threadId.isJsonPrimitive && threadId.asJsonPrimitive.isNumber, "Expected numeric exception thread_id")
                exceptionObject.addProperty("thread_id", 0)
                val frames = exceptionObject.getAsJsonObject("stacktrace")?.getAsJsonArray("frames") ?: return@forEach
                frames.forEach { frame ->
                    val frameObject = frame.asJsonObject
                    val filename = frameObject.get("filename")?.asString ?: return@forEach
                    val root = File(".").canonicalFile.path + File.separator
                    if (filename.startsWith(root)) {
                        frameObject.addProperty("filename", "<project>/" + filename.removePrefix(root).replace(File.separatorChar, '/'))
                    }
                }
            }
        }
    }

    private inline fun withServer(block: (MockWebServer) -> Unit) {
        val server = MockWebServer()
        server.start()
        try {
            block(server)
        } finally {
            server.shutdown()
        }
    }

    private inline fun withClient(
        postHog: PostHog,
        block: (PostHog) -> Unit,
    ) {
        try {
            block(postHog)
        } finally {
            postHog.close()
        }
    }

    private class FixedDateProvider(private val date: Date) : PostHogDateProvider {
        override fun currentDate(): Date = date

        override fun addSecondsToCurrentDate(seconds: Int): Date = Date(date.time + seconds * 1000L)

        override fun currentTimeMillis(): Long = date.time

        override fun nanoTime(): Long = date.time * 1_000_000L
    }

    private companion object {
        val PRETTY_GSON = GsonBuilder().setPrettyPrinting().create()
    }
}

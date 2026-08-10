package com.posthog.server

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import com.posthog.internal.PostHogLogger
import com.posthog.internal.parseISO8601Date
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import okio.GzipSource
import okio.buffer
import java.util.Date
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test utilities for posthog-server tests
 */

public const val TEST_API_KEY: String = "test-api-key"

// Executor utilities
public fun ExecutorService.awaitExecution() {
    // instead of using shutdownAndAwaitTermination which shutdown the executor
    // we schedule a task to be run and await for it to be completed
    submit {}.get()
}

public fun ExecutorService.shutdownAndAwaitTermination() {
    shutdown() // Disable new tasks from being submitted
    try {
        // Wait a while for existing tasks to terminate
        if (!awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)) {
            shutdownNow() // Cancel currently executing tasks
            // Wait a while for tasks to respond to being cancelled
            if (!awaitTermination(
                    60,
                    java.util.concurrent.TimeUnit.SECONDS,
                )
            ) {
                throw RuntimeException("Pool did not terminate")
            }
        }
    } catch (ie: InterruptedException) {
        // (Re-)Cancel if current thread also interrupted
        shutdownNow()
        // Preserve interrupt status
        Thread.currentThread().interrupt()
    }
}

// Event generation utilities
public val date: Date = parseISO8601Date("2023-09-20T11:58:49.000Z")!!
public const val EVENT: String = "event"
public const val DISTINCT_ID: String = "distinctId"
public val props: Map<String, Any> = mapOf<String, Any>("prop" to "value")
public val uuid: UUID = UUID.fromString("8c04e5c1-8f6e-4002-96fd-1804799b6ffe")

public fun generateEvent(
    eventName: String? = null,
    givenUuid: UUID? = null,
): PostHogEvent {
    return PostHogEvent(
        eventName ?: EVENT,
        distinctId = DISTINCT_ID,
        properties = props.toMutableMap(),
        timestamp = date,
        uuid = givenUuid ?: uuid,
    )
}

// HTTP utilities
public fun Buffer.unGzip(): String {
    return GzipSource(this).use { source ->
        source.buffer().use { bufferedSource -> bufferedSource.readUtf8() }
    }
}

private val gson = Gson()

/**
 * Represents a captured batch request with parsed events
 */
public class BatchRequest(private val json: JsonObject) {
    public val batch: List<JsonObject> by lazy {
        json.getAsJsonArray("batch")?.map { it.asJsonObject } ?: emptyList()
    }

    public val firstEvent: JsonObject?
        get() = batch.firstOrNull()

    public fun firstEventProperties(): Map<String, Any?> {
        val props = firstEvent?.getAsJsonObject("properties") ?: return emptyMap()
        return gson.fromJson(props, object : TypeToken<Map<String, Any?>>() {}.type)
    }

    /**
     * Find an event by name
     */
    public fun findEvent(eventName: String): JsonObject? {
        return batch.find { it.get("event")?.asString == eventName }
    }

    /**
     * Get properties for an event by name
     */
    public fun eventProperties(eventName: String): Map<String, Any?> {
        val props = findEvent(eventName)?.getAsJsonObject("properties") ?: return emptyMap()
        return gson.fromJson(props, object : TypeToken<Map<String, Any?>>() {}.type)
    }
}

/**
 * Every `$feature_flag_called` event across all `/batch` requests, as (flag key, properties) pairs.
 * Spans requests because `flushAt(1)` splits accesses over several batches.
 *
 * Consumes each request body, so call this once per drained list.
 */
public fun List<RecordedRequest>.featureFlagCalledEvents(): List<Pair<String, Map<String, Any?>>> {
    return filter { it.path?.contains("/batch") == true }
        .flatMap { it.parseBatch().batch }
        .filter { it.get("event")?.asString == "\$feature_flag_called" }
        .mapNotNull { event ->
            val props: Map<String, Any?> =
                gson.fromJson(
                    event.getAsJsonObject("properties"),
                    object : TypeToken<Map<String, Any?>>() {}.type,
                )
            val key = props["\$feature_flag"] as? String ?: return@mapNotNull null
            key to props
        }
}

/**
 * Routes `/local_evaluation` and `/flags` to the given responses and counts how many times each was
 * asked for. Routes by path rather than by enqueue order, because the local evaluation poller fires
 * asynchronously and would otherwise race the request under test for the head of the queue.
 */
public class CountingDispatcher(
    private val localEvaluationResponse: () -> MockResponse,
    private val flagsResponse: () -> MockResponse,
) : Dispatcher() {
    public val flagsCalls: AtomicInteger = AtomicInteger(0)
    public val localEvaluationCalls: AtomicInteger = AtomicInteger(0)

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path ?: ""
        return when {
            path.contains("local_evaluation") -> {
                localEvaluationCalls.incrementAndGet()
                localEvaluationResponse()
            }
            path.contains("/flags") -> {
                flagsCalls.incrementAndGet()
                flagsResponse()
            }
            else -> MockResponse().setResponseCode(200)
        }
    }
}

/**
 * Parse batch request body into structured format
 */
public fun RecordedRequest.parseBatch(): BatchRequest {
    val bodyString = body.unGzip()
    return BatchRequest(gson.fromJson(bodyString, JsonObject::class.java))
}

/**
 * Mock logger that captures log messages for test verification
 */
public class TestLogger : PostHogLogger {
    public val logs: MutableList<String> = mutableListOf()

    override fun log(message: String) {
        logs.add(message)
    }

    override fun isEnabled(): Boolean = true

    public fun clear() {
        logs.clear()
    }

    public fun containsLog(substring: String): Boolean = logs.any { it.contains(substring) }

    public fun countLogs(substring: String): Int = logs.count { it.contains(substring) }
}

/**
 * Creates a mock HTTP server with the given response
 */
public fun createMockHttp(response: MockResponse = MockResponse().setBody("")): MockWebServer {
    val mockServer = MockWebServer()
    mockServer.start()
    mockServer.enqueue(response)
    return mockServer
}

/**
 * Creates a mock HTTP server with multiple responses
 */
public fun createMockHttp(vararg responses: MockResponse): MockWebServer {
    val mockServer = MockWebServer()
    mockServer.start()
    responses.forEach { mockServer.enqueue(it) }
    return mockServer
}

/**
 * Creates a PostHogConfig for testing
 */
public fun createTestConfig(
    logger: PostHogLogger = TestLogger(),
    host: String = "https://example.com",
    apiKey: String = TEST_API_KEY,
): PostHogConfig {
    val config =
        PostHogConfig(
            apiKey = apiKey,
            host = host,
        )
    config.logger = logger
    return config
}

/**
 * Creates a standard JSON response for feature flags
 */
public fun createFlagsResponse(
    flagKey: String,
    enabled: Boolean = true,
    variant: String? = null,
    payload: String? = null,
): String {
    val payloadJson = if (payload != null) "\"$payload\"" else "null"
    val variantJson = if (variant != null) "\"$variant\"" else "null"

    return """
        {
            "flags": {
                "$flagKey": {
                    "key": "$flagKey",
                    "enabled": $enabled,
                    "variant": $variantJson,
                    "metadata": {
                        "version": 1,
                        "payload": $payloadJson,
                        "id": 1
                    },
                    "reason": {
                        "kind": "condition_match",
                        "condition_match_type": "Test condition",
                        "condition_index": 0
                    }
                }
            }
        }
        """.trimIndent()
}

/**
 * Creates a JSON response with multiple feature flags
 */
public fun createMultipleFlagsResponse(vararg flags: Pair<String, Boolean>): String {
    val flagsJson =
        flags.joinToString(",\n") { (key, enabled) ->
            """
            "$key": {
                "key": "$key",
                "enabled": $enabled,
                "variant": null,
                "metadata": {
                    "version": 1,
                    "payload": null,
                    "id": 1
                },
                "reason": {
                    "kind": "condition_match",
                    "condition_match_type": "Test condition",
                    "condition_index": 0
                }
            }
            """.trimIndent()
        }

    return """
        {
            "flags": {
                $flagsJson
            }
        }
        """.trimIndent()
}

/**
 * Creates an empty flags response
 */
public fun createEmptyFlagsResponse(): String {
    return """
        {
            "flags": {}
        }
        """.trimIndent()
}

/**
 * Creates a flags response with errors while computing
 */
public fun createFlagsResponseWithErrors(
    flagKey: String? = null,
    enabled: Boolean = true,
    variant: String? = null,
): String {
    val flagsJson =
        if (flagKey != null) {
            val variantJson = if (variant != null) "\"$variant\"" else "null"
            """
            "$flagKey": {
                "key": "$flagKey",
                "enabled": $enabled,
                "variant": $variantJson,
                "metadata": {
                    "version": 1,
                    "payload": null,
                    "id": 1
                },
                "reason": {
                    "kind": "condition_match",
                    "condition_match_type": "Test condition",
                    "condition_index": 0
                }
            }
            """.trimIndent()
        } else {
            ""
        }

    val flagsBlock =
        if (flagKey != null) {
            """
            "flags": {
                $flagsJson
            },
            """.trimIndent()
        } else {
            """
            "flags": {},
            """.trimIndent()
        }

    return """
        {
            $flagsBlock
            "errorsWhileComputingFlags": true
        }
        """.trimIndent()
}

/**
 * Creates a flags response with quota limited error
 */
public fun createFlagsResponseWithQuotaLimited(
    flagKey: String? = null,
    enabled: Boolean = true,
    variant: String? = null,
): String {
    val flagsJson =
        if (flagKey != null) {
            val variantJson = if (variant != null) "\"$variant\"" else "null"
            """
            "$flagKey": {
                "key": "$flagKey",
                "enabled": $enabled,
                "variant": $variantJson,
                "metadata": {
                    "version": 1,
                    "payload": null,
                    "id": 1
                },
                "reason": {
                    "kind": "condition_match",
                    "condition_match_type": "Test condition",
                    "condition_index": 0
                }
            }
            """.trimIndent()
        } else {
            ""
        }

    val flagsBlock =
        if (flagKey != null) {
            """
            "flags": {
                $flagsJson
            },
            """.trimIndent()
        } else {
            """
            "flags": {},
            """.trimIndent()
        }

    return """
        {
            $flagsBlock
            "quotaLimited": ["feature_flags"]
        }
        """.trimIndent()
}

/**
 * Creates a MockResponse with JSON content type
 */
public fun jsonResponse(body: String): MockResponse {
    return MockResponse()
        .setBody(body)
        .setHeader("Content-Type", "application/json")
}

/**
 * Creates a MockResponse with JSON content type and ETag header
 */
public fun jsonResponseWithEtag(
    body: String,
    etag: String,
): MockResponse {
    return MockResponse()
        .setBody(body)
        .setHeader("Content-Type", "application/json")
        .setHeader("ETag", etag)
}

/**
 * Creates a 304 Not Modified response with optional ETag header
 */
public fun notModifiedResponse(etag: String? = null): MockResponse {
    val response = MockResponse().setResponseCode(304)
    if (etag != null) {
        response.setHeader("ETag", etag)
    }
    return response
}

/**
 * Creates a MockResponse with error status
 */
public fun errorResponse(
    code: Int,
    message: String = "Error",
): MockResponse {
    return MockResponse()
        .setResponseCode(code)
        .setBody(message)
}

/**
 * Creates a mock PostHogEncryption implementation for testing
 */
public fun createMockEncryption(): com.posthog.PostHogEncryption {
    return object : com.posthog.PostHogEncryption {
        override fun encrypt(outputStream: java.io.OutputStream): java.io.OutputStream = outputStream

        override fun decrypt(inputStream: java.io.InputStream): java.io.InputStream = inputStream
    }
}

/**
 * Creates a mock PostHogBeforeSend implementation for testing
 */
public fun createMockBeforeSend(): com.posthog.PostHogBeforeSend {
    return com.posthog.PostHogBeforeSend { event -> event }
}

/**
 * Creates a mock PostHogIntegration implementation for testing
 */
public fun createMockIntegration(): com.posthog.PostHogIntegration {
    return object : com.posthog.PostHogIntegration {
        // Using default implementations from interface
    }
}

/**
 * Flag definition JSON for a flag local evaluation can always resolve: active, 100% rollout, no
 * property conditions.
 */
public fun conclusiveFlagDefinition(key: String): String {
    return """
        {
            "id": 1,
            "name": "$key",
            "key": "$key",
            "active": true,
            "filters": {
                "groups": [
                    { "properties": [], "rollout_percentage": 100 }
                ]
            },
            "version": 1
        }
        """.trimIndent()
}

/**
 * Flag definition JSON gated on `email icontains @acme.com`. Local evaluation is inconclusive for
 * this flag unless the caller supplies an `email` person property.
 */
public fun emailGatedFlagDefinition(key: String): String {
    return """
        {
            "id": 2,
            "name": "$key",
            "key": "$key",
            "active": true,
            "filters": {
                "groups": [
                    {
                        "properties": [
                            {
                                "key": "email",
                                "type": "person",
                                "value": "@acme.com",
                                "operator": "icontains"
                            }
                        ],
                        "rollout_percentage": 100
                    }
                ]
            },
            "version": 1
        }
        """.trimIndent()
}

/**
 * Flag definition JSON that parses but makes the local evaluator throw a plain
 * [NullPointerException] (not an `InconclusiveMatchException`): Gson deserializes the `null`
 * multivariate variant into the non-null `List<VariantDefinition>`, and evaluation trips over it
 * before any condition is checked.
 */
public fun throwingFlagDefinition(key: String): String {
    return """
        {
            "id": 3,
            "name": "$key",
            "key": "$key",
            "active": true,
            "filters": {
                "groups": [
                    { "properties": [], "rollout_percentage": 100 }
                ],
                "multivariate": { "variants": [null] }
            },
            "version": 1
        }
        """.trimIndent()
}

/**
 * Creates a local evaluation API response from raw flag definition JSON, for the fixtures
 * [createLocalEvaluationResponse] cannot express: several flags, property conditions, dependencies.
 */
public fun createLocalEvaluationResponseFrom(vararg flagDefinitions: String): String {
    return """
        {
            "flags": [ ${flagDefinitions.joinToString(",")} ],
            "group_type_mapping": {},
            "cohorts": {}
        }
        """.trimIndent()
}

/**
 * Creates a local evaluation API response for testing
 */
public fun createLocalEvaluationResponse(
    flagKey: String,
    aggregationGroupTypeIndex: Int? = null,
    rolloutPercentage: Int = 100,
    hasExperiment: Boolean? = null,
): String {
    val aggregationGroupJson =
        if (aggregationGroupTypeIndex != null) {
            "\"aggregation_group_type_index\": $aggregationGroupTypeIndex,"
        } else {
            ""
        }
    val hasExperimentJson =
        if (hasExperiment != null) {
            "\"has_experiment\": $hasExperiment,"
        } else {
            ""
        }

    return """
        {
            "flags": [
                {
                    "id": 1,
                    "name": "$flagKey",
                    "key": "$flagKey",
                    "active": true,
                    $hasExperimentJson
                    "filters": {
                        $aggregationGroupJson
                        "groups": [
                            {
                                "properties": [],
                                "rollout_percentage": $rolloutPercentage
                            }
                        ]
                    },
                    "version": 1
                }
            ],
            "group_type_mapping": {
                "0": "account",
                "1": "instance",
                "2": "organization",
                "3": "project"
            },
            "cohorts": {}
        }
        """.trimIndent()
}

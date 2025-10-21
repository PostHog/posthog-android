package com.posthog.server

import com.google.gson.internal.bind.util.ISO8601Utils
import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import com.posthog.internal.PostHogLogger
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.GzipSource
import okio.buffer
import java.text.ParsePosition
import java.util.Date
import java.util.UUID
import java.util.concurrent.ExecutorService

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
public val date: Date = ISO8601Utils.parse("2023-09-20T11:58:49.000Z", ParsePosition(0))
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
 * Creates a MockResponse with JSON content type
 */
public fun jsonResponse(body: String): MockResponse {
    return MockResponse()
        .setBody(body)
        .setHeader("Content-Type", "application/json")
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
 * Creates a local evaluation API response for testing
 */
public fun createLocalEvaluationResponse(
    flagKey: String,
    aggregationGroupTypeIndex: Int? = null,
    rolloutPercentage: Int = 100,
): String {
    val aggregationGroupJson =
        if (aggregationGroupTypeIndex != null) {
            "\"aggregation_group_type_index\": $aggregationGroupTypeIndex,"
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

package com.posthog

import com.google.gson.internal.bind.util.ISO8601Utils
import com.posthog.internal.PostHogContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.GzipSource
import okio.buffer
import java.lang.RuntimeException
import java.text.ParsePosition
import java.util.Date
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

public const val API_KEY: String = "_6SG-F7I1vCuZ-HdJL3VZQqjBlaSb1_20hDPwqMNnGI"

public fun ExecutorService.shutdownAndAwaitTermination() {
    shutdown() // Disable new tasks from being submitted
    try {
        // Wait a while for existing tasks to terminate
        if (!awaitTermination(60, TimeUnit.SECONDS)) {
            shutdownNow() // Cancel currently executing tasks
            // Wait a while for tasks to respond to being cancelled
            if (!awaitTermination(
                    60,
                    TimeUnit.SECONDS,
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

public fun ExecutorService.awaitExecution() {
    // instead of using shutdownAndAwaitTermination which shutdown the executor
    // we schedule a task to be run and await for it to be completed
    submit {}.get()
}

public val date: Date = ISO8601Utils.parse("2023-09-20T11:58:49.000Z", ParsePosition(0))
public const val EVENT: String = "event"
public const val DISTINCT_ID: String = "distinctId"
public const val ANON_ID: String = "anonId"
public val groups: Map<String, String> = mapOf("group1" to "theValue")
public val userProps: Map<String, Any> = mapOf("user1" to "theValue")
public val userPropsOnce: Map<String, Any> = mapOf("logged" to true)
public val groupProps: Map<String, Any> = mapOf("premium" to true)
public val props: Map<String, Any> = mapOf<String, Any>("prop" to "value")
public val uuid: UUID = UUID.fromString("8c04e5c1-8f6e-4002-96fd-1804799b6ffe")

public fun generateEvent(
    eventName: String? = null,
    givenUuuid: UUID? = null,
): PostHogEvent {
    return PostHogEvent(
        eventName ?: EVENT,
        distinctId = DISTINCT_ID,
        properties = props.toMutableMap(),
        timestamp = date,
        uuid = givenUuuid ?: uuid,
    )
}

public fun mockHttp(
    total: Int = 1,
    response: MockResponse =
        MockResponse()
            .setBody(""),
): MockWebServer {
    val mock = MockWebServer()
    mock.start()
    for (i in 1..total) {
        mock.enqueue(response)
    }
    return mock
}

public fun Buffer.unGzip(): String {
    return GzipSource(this).use { source ->
        source.buffer().use { bufferedSource -> bufferedSource.readUtf8() }
    }
}

public class TestPostHogContext : PostHogContext {
    override fun getStaticContext(): Map<String, Any> =
        mapOf(
            "\$app_version" to "1.0.0",
            "\$app_build" to "100",
            "\$app_namespace" to "my-namespace",
            "\$os_name" to "Android",
            "\$os_version" to "13",
            "\$device_type" to "Mobile",
        )

    override fun getDynamicContext(): Map<String, Any> = emptyMap()

    override fun getSdkInfo(): Map<String, Any> =
        mapOf(
            "\$lib" to "posthog-android",
            "\$lib_version" to "1.2.3",
        )
}

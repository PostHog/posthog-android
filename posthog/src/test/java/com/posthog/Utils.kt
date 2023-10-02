package com.posthog

import com.google.gson.internal.bind.util.ISO8601Utils
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.lang.RuntimeException
import java.text.ParsePosition
import java.util.Date
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

public const val apiKey: String = "_6SG-F7I1vCuZ-HdJL3VZQqjBlaSb1_20hDPwqMNnGI"

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

public val date: Date = ISO8601Utils.parse("2023-09-20T11:58:49.000Z", ParsePosition(0))
public const val event: String = "event"
public const val distinctId: String = "distinctId"
public const val anonId: String = "anonId"
public val groups: Map<String, Any> = mapOf("group1" to "theValue")
public val props: Map<String, Any> = mapOf<String, Any>("prop" to "value")
public val uuid: UUID = UUID.fromString("8c04e5c1-8f6e-4002-96fd-1804799b6ffe")

public fun generateEvent(eventName: String? = null): PostHogEvent {
    return PostHogEvent(
        eventName ?: event,
        distinctId = distinctId,
        properties = props,
        timestamp = date,
        uuid = uuid,
    )
}

public const val responseApi: String = """
{
  "autocaptureExceptions": false,
  "toolbarParams": {},
  "errorsWhileComputingFlags": false,
  "capturePerformance": true,
  "autocapture_opt_out": false,
  "isAuthenticated": false,
  "supportedCompression": [
    "gzip",
    "gzip-js"
  ],
  "config": {
    "enable_collect_everything": true
  },
  "featureFlagPayloads": {
    "thePayload": true
  },
  "featureFlags": {
    "4535-funnel-bar-viz": true
  },
  "sessionRecording": false,
  "siteApps": [
    {
      "id": 21039.0,
      "url": "/site_app/21039/EOsOSePYNyTzHkZ3f4mjrjUap8Hy8o2vUTAc6v1ZMFP/576ac89bc8aed72a21d9b19221c2c626/"
    }
  ],
  "editorParams": {

  }
}
    """

public fun mockHttp(
    total: Int = 1,
    response: MockResponse = MockResponse()
        .setBody(""),
): MockWebServer {
    val mock = MockWebServer()
    mock.start()
    for (i in 1..total) {
        mock.enqueue(response)
    }
    return mock
}

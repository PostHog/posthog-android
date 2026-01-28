package com.posthog.compliance

import com.posthog.BuildConfig
import com.posthog.PostHog
import com.posthog.PostHogConfig
import com.posthog.internal.GzipRequestInterceptor
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.gson.gson
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import okhttp3.Interceptor
import okhttp3.Response
import com.posthog.internal.PostHogContext
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * PostHog Android SDK Compliance Adapter
 *
 * HTTP wrapper around the PostHog Android SDK for compliance testing.
 */

data class RequestRecord(
    val timestamp_ms: Long,
    val status_code: Int,
    val retry_attempt: Int,
    val event_count: Int,
    val uuid_list: List<String>
)

data class AdapterState(
    var pendingEvents: Int = 0,
    var totalEventsCaptured: Int = 0,
    var totalEventsSent: Int = 0,
    var totalRetries: Int = 0,
    var lastError: String? = null,
    val requestsMade: MutableList<RequestRecord> = mutableListOf()
)

// Request/Response models
data class HealthResponse(
    val sdk_name: String,
    val sdk_version: String,
    val adapter_version: String
)

data class InitRequest(
    val api_key: String,
    val host: String,
    val flush_at: Int? = null,
    val flush_interval_ms: Int? = null,
    val max_retries: Int? = null,
    val enable_compression: Boolean? = null
)

data class CaptureRequest(
    val distinct_id: String,
    val event: String,
    val properties: Map<String, Any>? = null,
    val timestamp: String? = null
)

data class CaptureResponse(
    val success: Boolean,
    val uuid: String
)

data class FlushResponse(
    val success: Boolean,
    val events_flushed: Int
)

data class StateResponse(
    val pending_events: Int,
    val total_events_captured: Int,
    val total_events_sent: Int,
    val total_retries: Int,
    val last_error: String?,
    val requests_made: List<RequestRecord>
)

data class SuccessResponse(
    val success: Boolean
)

// Minimal context for testing (provides $lib and $lib_version)
class TestPostHogContext(private val sdkName: String, private val sdkVersion: String) : PostHogContext {
    override fun getStaticContext(): Map<String, Any> = emptyMap()
    override fun getDynamicContext(): Map<String, Any> = emptyMap()
    override fun getSdkInfo(): Map<String, Any> = mapOf(
        "\$lib" to sdkName,
        "\$lib_version" to sdkVersion
    )
}

// Global state
object AdapterContext {
    val state = AdapterState()
    val lock = ReentrantLock()
    var postHog: com.posthog.PostHogInterface? = null
    val capturedEvents = mutableListOf<String>() // Store UUIDs of captured events
}

// OkHttp Interceptor to track requests
class TrackingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()

        println("[ADAPTER] Intercepting request to: $url")

        // Copy the request body so we can read it
        val requestBody = request.body
        var eventCount = 0
        val uuidList = mutableListOf<String>()

        // Parse request body before sending
        if (url.contains("/batch") && !url.contains("/flags") && requestBody != null) {
            try {
                val buffer = okio.Buffer()
                requestBody.writeTo(buffer)
                val bodyString = buffer.readUtf8()

                println("[ADAPTER] Request body: ${bodyString.take(200)}...")

                // Parse JSON to extract UUIDs
                // The body format is: {"api_key": "...", "batch": [{event}, {event}], "sent_at": "..."}
                val uuidRegex = """"uuid"\s*:\s*"([^"]+)"""".toRegex()
                uuidRegex.findAll(bodyString).forEach { match ->
                    uuidList.add(match.groupValues[1])
                }
                eventCount = uuidList.size

                println("[ADAPTER] Extracted $eventCount events with UUIDs: ${uuidList.joinToString()}")
            } catch (e: Exception) {
                println("[ADAPTER] Error parsing request body: ${e.message}")
                e.printStackTrace()
            }
        }

        val response = chain.proceed(request)

        // Track the response
        if (url.contains("/batch") && !url.contains("/flags")) {
            println("[ADAPTER] Tracking batch request: status=${response.code}")

            try {
                // Extract retry count from URL if present
                val retryCount = request.url.queryParameter("retry_count")?.toIntOrNull() ?: 0

                AdapterContext.lock.withLock {
                    val record = RequestRecord(
                        timestamp_ms = System.currentTimeMillis(),
                        status_code = response.code,
                        retry_attempt = retryCount,
                        event_count = eventCount,
                        uuid_list = uuidList
                    )

                    AdapterContext.state.requestsMade.add(record)

                    if (response.isSuccessful) {
                        AdapterContext.state.totalEventsSent += eventCount
                        AdapterContext.state.pendingEvents = maxOf(0, AdapterContext.state.pendingEvents - eventCount)
                    }

                    if (retryCount > 0) {
                        AdapterContext.state.totalRetries++
                    }
                }

                println("[ADAPTER] Recorded request: status=${response.code}, retry=$retryCount, events=$eventCount")
            } catch (e: Exception) {
                println("[ADAPTER] Error tracking request: ${e.message}")
                e.printStackTrace()
            }
        }

        return response
    }
}

fun main() {
    println("[ADAPTER] Starting PostHog Android SDK Compliance Adapter")

    embeddedServer(Netty, port = 8080) {
        install(ContentNegotiation) {
            gson {
                setPrettyPrinting()
            }
        }

        routing {
            get("/health") {
                println("[ADAPTER] GET /health")
                call.respond(
                    HealthResponse(
                        sdk_name = "posthog-android",
                        sdk_version = BuildConfig.VERSION_NAME,
                        adapter_version = "1.0.0"
                    )
                )
            }

            post("/init") {
                println("[ADAPTER] POST /init")
                val req = call.receive<InitRequest>()
                println("[ADAPTER] Initializing with api_key=${req.api_key}, host=${req.host}")

                AdapterContext.lock.withLock {
                    // Reset state
                    AdapterContext.state.pendingEvents = 0
                    AdapterContext.state.totalEventsCaptured = 0
                    AdapterContext.state.totalEventsSent = 0
                    AdapterContext.state.totalRetries = 0
                    AdapterContext.state.lastError = null
                    AdapterContext.state.requestsMade.clear()
                    AdapterContext.capturedEvents.clear()

                    // Close existing instance if any
                    AdapterContext.postHog?.close()

                    // Create config first (needed for GzipRequestInterceptor)
                    val tempConfig = PostHogConfig(apiKey = req.api_key, host = req.host)

                    // Create OkHttpClient with tracking interceptor first, then gzip
                    // Order matters: TrackingInterceptor reads uncompressed body, GzipInterceptor compresses it
                    val httpClient = okhttp3.OkHttpClient.Builder()
                        .addInterceptor(TrackingInterceptor())
                        .addInterceptor(GzipRequestInterceptor(tempConfig))
                        .build()

                    // Create new config
                    val flushIntervalMs = req.flush_interval_ms ?: 100
                    val flushIntervalSeconds = maxOf(1, flushIntervalMs / 1000) // Min 1 second

                    val config = PostHogConfig(
                        apiKey = req.api_key,
                        host = req.host,
                        flushAt = req.flush_at ?: 1,
                        flushIntervalSeconds = flushIntervalSeconds,
                        debug = true,
                        httpClient = httpClient,
                        preloadFeatureFlags = false
                    )

                    // Set storage prefix for file-backed queue
                    config.storagePrefix = "/tmp/posthog-queue"

                    // Set minimal context to provide $lib and $lib_version
                    config.context = TestPostHogContext("posthog-android", BuildConfig.VERSION_NAME)

                    // Add beforeSend hook to track captured events
                    config.addBeforeSend { event ->
                        AdapterContext.lock.withLock {
                            event.uuid?.let { uuid ->
                                AdapterContext.capturedEvents.add(uuid.toString())
                            }
                        }
                        event
                    }

                    // Create PostHog instance
                    AdapterContext.postHog = PostHog.with(config)

                    println("[ADAPTER] PostHog initialized with tracking interceptor")
                }

                call.respond(SuccessResponse(success = true))
            }

            post("/capture") {
                println("[ADAPTER] POST /capture")
                val req = call.receive<CaptureRequest>()
                println("[ADAPTER] Capturing event: ${req.event} for user: ${req.distinct_id}")

                val ph = AdapterContext.lock.withLock {
                    AdapterContext.postHog
                }

                if (ph == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "SDK not initialized"))
                    return@post
                }

                // Remember the count before capturing
                val beforeCount = AdapterContext.capturedEvents.size

                // Capture event with distinct_id parameter (don't call identify separately)
                val properties = req.properties?.toMutableMap() ?: mutableMapOf()
                ph.capture(
                    event = req.event,
                    distinctId = req.distinct_id,
                    properties = properties
                )

                // Get the UUID that was just captured (via beforeSend hook)
                val uuid = AdapterContext.lock.withLock {
                    AdapterContext.state.totalEventsCaptured++
                    AdapterContext.state.pendingEvents++

                    // The last UUID added is the one we just captured
                    if (AdapterContext.capturedEvents.size > beforeCount) {
                        AdapterContext.capturedEvents.last()
                    } else {
                        // Fallback if beforeSend didn't fire yet
                        UUID.randomUUID().toString()
                    }
                }

                call.respond(CaptureResponse(success = true, uuid = uuid))
            }

            post("/flush") {
                println("[ADAPTER] POST /flush")

                AdapterContext.postHog?.flush()

                // Wait for events to be sent (generous timeout for Docker network latency)
                Thread.sleep(2000)

                val eventsFlushed = AdapterContext.lock.withLock {
                    val flushed = AdapterContext.state.totalEventsSent
                    AdapterContext.state.pendingEvents = 0
                    flushed
                }

                println("[ADAPTER] Flush complete: $eventsFlushed events sent")

                call.respond(FlushResponse(success = true, events_flushed = eventsFlushed))
            }

            get("/state") {
                println("[ADAPTER] GET /state")

                val stateSnapshot = AdapterContext.lock.withLock {
                    StateResponse(
                        pending_events = AdapterContext.state.pendingEvents,
                        total_events_captured = AdapterContext.state.totalEventsCaptured,
                        total_events_sent = AdapterContext.state.totalEventsSent,
                        total_retries = AdapterContext.state.totalRetries,
                        last_error = AdapterContext.state.lastError,
                        requests_made = AdapterContext.state.requestsMade.toList()
                    )
                }

                println("[ADAPTER] State: $stateSnapshot")

                call.respond(stateSnapshot)
            }

            post("/reset") {
                println("[ADAPTER] POST /reset")

                AdapterContext.lock.withLock {
                    AdapterContext.postHog?.reset()

                    AdapterContext.state.pendingEvents = 0
                    AdapterContext.state.totalEventsCaptured = 0
                    AdapterContext.state.totalEventsSent = 0
                    AdapterContext.state.totalRetries = 0
                    AdapterContext.state.lastError = null
                    AdapterContext.state.requestsMade.clear()
                    AdapterContext.capturedEvents.clear()
                }

                call.respond(SuccessResponse(success = true))
            }
        }
    }.start(wait = true)
}

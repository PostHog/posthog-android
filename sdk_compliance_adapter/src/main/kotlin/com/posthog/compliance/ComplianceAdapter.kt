package com.posthog.compliance

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.posthog.PersonProfiles
import com.posthog.PostHog
import com.posthog.PostHogCompression
import com.posthog.PostHogConfig
import com.posthog.internal.GzipRequestInterceptor
import com.posthog.internal.PostHogApi
import com.posthog.internal.PostHogContext
import com.posthog.internal.PostHogLogger
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
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.Collections
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// File-backed event queue; wiped between tests (see /init and /reset).
private const val QUEUE_STORAGE_PREFIX = "/tmp/posthog-queue"

// Upper bound on the wait for a flush()'d batch to reach the mock (generous for Docker).
private const val FLUSH_SETTLE_MS = 2000L

// The capture v1 endpoints the SDK posts analytics and AI events to.
private const val CAPTURE_PATH = "/i/v1/analytics/events"
private const val CAPTURE_AI_PATH = "/i/v1/ai/events"

private fun isCaptureUrl(url: String): Boolean = url.contains(CAPTURE_PATH) || url.contains(CAPTURE_AI_PATH)

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
    val uuid_list: List<String>,
)

data class AdapterState(
    var pendingEvents: Int = 0,
    var totalEventsCaptured: Int = 0,
    var totalEventsSent: Int = 0,
    var totalRetries: Int = 0,
    var lastError: String? = null,
    val requestsMade: MutableList<RequestRecord> = mutableListOf(),
)

// Request/Response models
data class HealthResponse(
    val sdk_name: String,
    val sdk_version: String,
    val adapter_version: String,
    val capabilities: List<String>,
)

data class InitRequest(
    val api_key: String,
    val host: String,
    val flush_at: Int? = null,
    val flush_interval_ms: Int? = null,
    val max_retries: Int? = null,
    val enable_compression: Boolean? = null,
    val super_properties: Map<String, Any>? = null,
    val before_send: BeforeSendSpec? = null,
)

data class BeforeSendSpec(
    val set_properties: Map<String, Any>? = null,
)

data class CaptureRequest(
    val distinct_id: String,
    val event: String,
    val properties: Map<String, Any>? = null,
    val timestamp: String? = null,
    val options: Map<String, Any?>? = null,
    val pad_properties_to_bytes: Int? = null,
    val marker: String? = null,
)

data class CaptureResponse(
    val success: Boolean,
    // Null (omitted from the JSON) when the SDK didn't queue the event.
    val uuid: String? = null,
)

data class FlagRequest(
    val key: String,
    val distinct_id: String? = null,
    val person_properties: Map<String, Any>? = null,
    val groups: Map<String, String>? = null,
    val group_properties: Map<String, Map<String, Any>>? = null,
    // Decoded for harness parity but not applied: no public per-request geoip toggle and
    // the SDK always loads remotely (known SDK gaps).
    val disable_geoip: Boolean? = null,
    val force_remote: Boolean? = null,
)

data class FlagResponse(
    val success: Boolean,
    val value: Any?,
)

data class FlushResponse(
    val success: Boolean,
    val events_flushed: Int,
)

data class StateResponse(
    val pending_events: Int,
    val total_events_captured: Int,
    val total_events_sent: Int,
    val total_retries: Int,
    val last_error: String?,
    val requests_made: List<RequestRecord>,
    val log_lines: List<String>,
)

data class SuccessResponse(
    val success: Boolean,
)

// Minimal context for testing (provides $lib and $lib_version)
class TestPostHogContext(private val sdkName: String, private val sdkVersion: String) : PostHogContext {
    override fun getStaticContext(): Map<String, Any> = emptyMap()

    override fun getDynamicContext(): Map<String, Any> = emptyMap()

    override fun getSdkInfo(): Map<String, Any> =
        mapOf(
            "\$lib" to sdkName,
            "\$lib_version" to sdkVersion,
        )
}

// Records every SDK log line for /state, and still prints it.
class RecordingLogger : PostHogLogger {
    override fun log(message: String) {
        println(message)
        AdapterContext.logLines.add(message)
    }

    override fun isEnabled(): Boolean = true
}

// Global state
object AdapterContext {
    val state = AdapterState()
    val lock = ReentrantLock()

    // Signalled by the tracking interceptor when a capture response lands (see awaitBatch).
    val batchObserved: Condition = lock.newCondition()
    var postHog: com.posthog.PostHogInterface? = null
    var api: PostHogApi? = null
    val capturedEvents = mutableListOf<String>() // Store UUIDs of captured events

    // Own lock: the SDK logs from its threads, which must not wait on [lock].
    val logLines: MutableList<String> = Collections.synchronizedList(mutableListOf())
}

// OkHttp Interceptor to track requests
class TrackingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()

        // Copy the request body so we can read it
        val requestBody = request.body
        var eventCount = 0
        val uuidList = mutableListOf<String>()

        // Parse request body before sending
        if (isCaptureUrl(url) && requestBody != null) {
            try {
                val buffer = okio.Buffer()
                requestBody.writeTo(buffer)
                val bodyString = buffer.readUtf8()

                // Parse JSON to extract UUIDs
                // The body format is: {"created_at": "...", "batch": [{event}, {event}]}
                val uuidRegex = """"uuid"\s*:\s*"([^"]+)"""".toRegex()
                uuidRegex.findAll(bodyString).forEach { match ->
                    uuidList.add(match.groupValues[1])
                }
                eventCount = uuidList.size
            } catch (_: Exception) {
                // Ignore parsing errors
            }
        }

        val response = chain.proceed(request)

        // Track the response
        if (isCaptureUrl(url)) {
            try {
                // PostHog-Attempt starts at 1; the harness counts retries from 0.
                val retryCount = (request.header("PostHog-Attempt")?.toIntOrNull() ?: 1) - 1
                // Read before taking the lock, so a slow body can't stall /state, /reset or the other lane.
                val retried = if (response.isSuccessful) retriedCount(response, uuidList) else 0

                AdapterContext.lock.withLock {
                    val record =
                        RequestRecord(
                            timestamp_ms = System.currentTimeMillis(),
                            status_code = response.code,
                            retry_attempt = retryCount,
                            event_count = eventCount,
                            uuid_list = uuidList,
                        )

                    AdapterContext.state.requestsMade.add(record)

                    if (response.isSuccessful) {
                        // Events the server asks to retry stay queued and count once a later request delivers them.
                        val delivered = eventCount - retried
                        AdapterContext.state.totalEventsSent += delivered
                        AdapterContext.state.pendingEvents = maxOf(0, AdapterContext.state.pendingEvents - delivered)
                    }

                    if (retryCount > 0) {
                        AdapterContext.state.totalRetries++
                    }
                    AdapterContext.batchObserved.signalAll()
                }
            } catch (_: Exception) {
                // Ignore tracking errors
            }
        }

        return response
    }
}

// How many of [uuids] the capture v1 per-event results ask to retry. Peeks so the SDK still reads the
// body, within a deadline so a body that trickles in can't hold the request.
private fun retriedCount(
    response: Response,
    uuids: List<String>,
): Int {
    val timeout = response.body?.source()?.timeout()
    timeout?.deadline(RESPONSE_PEEK_DEADLINE_SECONDS, TimeUnit.SECONDS)
    return try {
        val body = response.peekBody(1024 * 1024).string()
        val results = JsonParser.parseString(body).asJsonObject.getAsJsonObject("results")
        uuids.count { uuid -> results?.getAsJsonObject(uuid)?.get("result")?.asString == "retry" }
    } catch (_: Exception) {
        0
    } finally {
        timeout?.clearDeadline()
    }
}

private const val RESPONSE_PEEK_DEADLINE_SECONDS = 5L

// The harness sends RFC 3339 timestamps, with any offset; the SDK serializes the instant in UTC.
private fun parseHarnessTimestamp(timestamp: String?): Date? =
    timestamp?.let {
        try {
            Date.from(OffsetDateTime.parse(it).toInstant())
        } catch (_: DateTimeParseException) {
            null
        }
    }

// Adds a $test_padding string so [properties], as compact UTF-8 JSON, is exactly [targetBytes] long.
private fun padProperties(
    properties: Map<String, Any>?,
    targetBytes: Int,
    marker: String?,
): Map<String, Any> {
    val padded = properties.orEmpty().toMutableMap()
    val prefix = marker.orEmpty()
    padded["\$test_padding"] = prefix
    // Measure with the marker in place so its serialized (escaped, UTF-8) size is counted.
    val baseBytes = Gson().toJson(padded).toByteArray(Charsets.UTF_8).size
    padded["\$test_padding"] = prefix + "x".repeat(maxOf(0, targetBytes - baseBytes))
    return padded
}

// Block until [predicate] holds (signalled as each capture response lands) or [timeoutMs] elapses.
private fun awaitBatch(
    timeoutMs: Long,
    predicate: () -> Boolean,
): Boolean =
    AdapterContext.lock.withLock {
        var remainingNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!predicate() && remainingNanos > 0) {
            remainingNanos = AdapterContext.batchObserved.awaitNanos(remainingNanos)
        }
        predicate()
    }

fun main() {
    embeddedServer(Netty, port = 8080) {
        install(ContentNegotiation) {
            gson {
                setPrettyPrinting()
            }
        }

        routing {
            get("/health") {
                call.respond(
                    HealthResponse(
                        sdk_name = "posthog-android",
                        sdk_version = PostHogConfig(projectToken = "").sdkVersion,
                        adapter_version = "1.0.0",
                        // Opt into the capture suites (android posts /i/v1/analytics/events and
                        // /i/v1/ai/events with gzip).
                        capabilities =
                            listOf(
                                "capture_v1",
                                "capture_ai_v1",
                                "encoding_gzip",
                                "event_options",
                                "generated_payloads",
                                "log_capture",
                                "property_layers",
                                "fail_open",
                            ),
                    ),
                )
            }

            post("/init") {
                val req = call.receive<InitRequest>()

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
                    AdapterContext.api = null
                    AdapterContext.logLines.clear()

                    // close() leaves queue files on disk; wipe so they don't replay into this test.
                    File(QUEUE_STORAGE_PREFIX).deleteRecursively()
                    File("$QUEUE_STORAGE_PREFIX-ai").deleteRecursively()

                    // Create new config
                    val flushIntervalMs = req.flush_interval_ms ?: 100
                    val flushIntervalSeconds = maxOf(1, flushIntervalMs / 1000) // Min 1 second

                    val config =
                        PostHogConfig(
                            projectToken = req.api_key,
                            host = req.host,
                            flushAt = req.flush_at ?: 1,
                            flushIntervalSeconds = flushIntervalSeconds,
                            debug = true,
                            preloadFeatureFlags = false,
                        )
                    config.logger = RecordingLogger()
                    // The harness models a server SDK: it never identifies, and expects person
                    // processing on unless an event opts out.
                    config.personProfiles = PersonProfiles.ALWAYS

                    // Create OkHttpClient with tracking and gzip interceptors
                    val httpClient =
                        okhttp3.OkHttpClient.Builder()
                            .addInterceptor(TrackingInterceptor())
                            .addInterceptor(GzipRequestInterceptor(config))
                            .build()

                    config.httpClient = httpClient

                    req.max_retries?.let { config.pushNotificationSubscriptionMaxRetries = it }
                    // The harness sends one enable_compression flag and no AI-lane setting, so it sets
                    // both lanes. With it unset, the AI lane keeps its documented default (gzip).
                    req.enable_compression?.let {
                        val compression = if (it) PostHogCompression.GZIP else PostHogCompression.NONE
                        config.compression = compression
                        config.captureAiCompression = compression
                    }

                    // Set storage prefixes for the file-backed queues (both wiped on /init and /reset)
                    config.storagePrefix = QUEUE_STORAGE_PREFIX
                    config.aiStoragePrefix = "$QUEUE_STORAGE_PREFIX-ai"

                    // Set minimal context to provide $lib and $lib_version
                    config.context = TestPostHogContext("posthog-android", config.sdkVersion)

                    // Add beforeSend hook to track captured events
                    config.addBeforeSend { event ->
                        AdapterContext.lock.withLock {
                            event.uuid?.let { uuid ->
                                AdapterContext.capturedEvents.add(uuid.toString())
                            }
                        }
                        event
                    }
                    req.before_send?.set_properties?.let { setProperties ->
                        config.addBeforeSend { event ->
                            event.properties?.putAll(setProperties)
                            event
                        }
                    }

                    // Create PostHog instance and direct API helper for per-request flag evaluation.
                    val postHog = PostHog.with(config)
                    req.super_properties?.forEach { (key, value) -> postHog.register(key, value) }
                    AdapterContext.postHog = postHog
                    AdapterContext.api = PostHogApi(config)
                }

                call.respond(SuccessResponse(success = true))
            }

            post("/capture") {
                val req = call.receive<CaptureRequest>()

                val ph =
                    AdapterContext.lock.withLock {
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
                    properties = properties,
                    timestamp = parseHarnessTimestamp(req.timestamp),
                    options = req.options,
                )

                // Get the UUID that was just captured (via beforeSend hook)
                val uuid =
                    AdapterContext.lock.withLock {
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

            // Same request as /capture, sent with captureAi to /i/v1/ai/events. Ktor's Netty engine
            // streams request bodies without a size limit, so an 8 MiB AI event is received whole.
            post("/capture_ai") {
                val req = call.receive<CaptureRequest>()

                val ph =
                    AdapterContext.lock.withLock {
                        AdapterContext.postHog
                    }

                if (ph == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "SDK not initialized"))
                    return@post
                }

                val properties =
                    req.pad_properties_to_bytes?.let { padProperties(req.properties, it, req.marker) }
                        ?: req.properties
                val uuid =
                    ph.captureAi(
                        event = req.event,
                        distinctId = req.distinct_id,
                        properties = properties,
                        timestamp = parseHarnessTimestamp(req.timestamp),
                        options = req.options,
                    )

                // A null uuid means the SDK didn't queue the event (for example it's over the size
                // limit), so no request will carry it and no uuid is reported.
                if (uuid != null) {
                    AdapterContext.lock.withLock {
                        AdapterContext.state.totalEventsCaptured++
                        AdapterContext.state.pendingEvents++
                    }
                }

                call.respond(CaptureResponse(success = true, uuid = uuid?.toString()))
            }

            post("/get_feature_flag") {
                val req = call.receive<FlagRequest>()

                val ph =
                    AdapterContext.lock.withLock {
                        AdapterContext.postHog
                    }

                if (ph == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "SDK not initialized"))
                    return@post
                }

                val api = AdapterContext.lock.withLock { AdapterContext.api }
                if (api == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "SDK not initialized"))
                    return@post
                }

                val distinctId = req.distinct_id ?: ph.distinctId()
                val response =
                    api.flags(
                        distinctId = distinctId,
                        anonymousId = ph.distinctId(),
                        deviceId = ph.distinctId(),
                        groups = req.groups ?: emptyMap(),
                        personProperties = req.person_properties,
                        groupProperties = req.group_properties,
                        flagKeys = listOf(req.key),
                        disableGeoip = req.disable_geoip ?: false,
                    )
                val flagDetails = response?.flags?.get(req.key)
                val value =
                    response?.featureFlags?.get(req.key)
                        ?: flagDetails?.let { it.variant ?: it.enabled }

                val requestsBefore = AdapterContext.lock.withLock { AdapterContext.state.requestsMade.size }
                val flagCalledProperties =
                    mutableMapOf<String, Any>(
                        "\$feature_flag" to req.key,
                        "\$feature_flag_response" to (value ?: false),
                        "\$feature/${req.key}" to (value ?: false),
                    )
                flagDetails?.metadata?.hasExperiment?.let { flagCalledProperties["\$feature_flag_has_experiment"] = it }
                ph.capture(
                    event = "\$feature_flag_called",
                    distinctId = distinctId,
                    properties = flagCalledProperties,
                )
                AdapterContext.lock.withLock {
                    AdapterContext.state.totalEventsCaptured++
                    AdapterContext.state.pendingEvents++
                }
                ph.flush()
                awaitBatch(FLUSH_SETTLE_MS) { AdapterContext.state.requestsMade.size > requestsBefore }

                call.respond(FlagResponse(success = true, value = value ?: false))
            }

            post("/flush") {
                val pendingBefore = AdapterContext.lock.withLock { AdapterContext.state.pendingEvents }
                AdapterContext.postHog?.flush()

                // Wait until every pending event's batch is acked; bounded so a no-op flush can't hang.
                if (pendingBefore > 0) {
                    awaitBatch(FLUSH_SETTLE_MS) { AdapterContext.state.pendingEvents == 0 }
                }

                val eventsFlushed =
                    AdapterContext.lock.withLock {
                        val flushed = AdapterContext.state.totalEventsSent
                        AdapterContext.state.pendingEvents = 0
                        flushed
                    }

                call.respond(FlushResponse(success = true, events_flushed = eventsFlushed))
            }

            get("/state") {
                val stateSnapshot =
                    AdapterContext.lock.withLock {
                        StateResponse(
                            pending_events = AdapterContext.state.pendingEvents,
                            total_events_captured = AdapterContext.state.totalEventsCaptured,
                            total_events_sent = AdapterContext.state.totalEventsSent,
                            total_retries = AdapterContext.state.totalRetries,
                            last_error = AdapterContext.state.lastError,
                            requests_made = AdapterContext.state.requestsMade.toList(),
                            log_lines = synchronized(AdapterContext.logLines) { AdapterContext.logLines.toList() },
                        )
                    }

                call.respond(stateSnapshot)
            }

            post("/reset") {
                AdapterContext.lock.withLock {
                    // close (not reset) — reset() reloads flags, lands as +1 /flags in the next test.
                    AdapterContext.postHog?.close()
                    AdapterContext.postHog = null
                    AdapterContext.api = null

                    // close() leaves queue files on disk; wipe so they don't replay next test (matches iOS).
                    File(QUEUE_STORAGE_PREFIX).deleteRecursively()
                    File("$QUEUE_STORAGE_PREFIX-ai").deleteRecursively()

                    AdapterContext.state.pendingEvents = 0
                    AdapterContext.state.totalEventsCaptured = 0
                    AdapterContext.state.totalEventsSent = 0
                    AdapterContext.state.totalRetries = 0
                    AdapterContext.state.lastError = null
                    AdapterContext.state.requestsMade.clear()
                    AdapterContext.capturedEvents.clear()
                    AdapterContext.logLines.clear()
                }

                call.respond(SuccessResponse(success = true))
            }
        }
    }.start(wait = true)
}

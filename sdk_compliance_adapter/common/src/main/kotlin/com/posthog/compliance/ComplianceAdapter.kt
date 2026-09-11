package com.posthog.compliance

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.ToNumberPolicy
import com.posthog.PostHogBeforeSend
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.uri
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.IgnoreTrailingSlash
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.time.OffsetDateTime
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

// Each initialization gets its own ingress URL and observation state, including during close().
data class InitRequest(
    val api_key: String,
    val host: String,
    val flush_at: Int? = null,
    val flush_interval_ms: Int? = null,
    val max_retries: Int? = null,
    val enable_compression: Boolean? = null,
)

data class CaptureRequest(
    val distinct_id: String,
    val event: String,
    val properties: Map<String, Any>? = null,
    val timestamp: String? = null,
) {
    fun date(): Date? = timestamp?.let { Date.from(OffsetDateTime.parse(it).toInstant()) }
}

data class FlagRequest(
    val key: String,
    val distinct_id: String? = null,
    val person_properties: Map<String, Any>? = null,
    val groups: Map<String, String>? = null,
    val group_properties: Map<String, Map<String, Any>>? = null,
    val disable_geoip: Boolean? = null,
    val force_remote: Boolean? = null,
)

interface SdkClient {
    fun capture(request: CaptureRequest)

    fun flag(request: FlagRequest): Any?

    fun flush()

    fun close()
}

interface SdkProfile {
    val name: String
    val version: String

    fun create(
        request: InitRequest,
        storage: File,
        observer: Observation,
    ): SdkClient
}

data class RequestRecord(
    val timestamp_ms: Long,
    val status_code: Int,
    val retry_attempt: Int,
    val event_count: Int,
    val uuid_list: List<String>,
)

class Observation {
    private val captured = linkedSetOf<String>()
    private val sent = mutableSetOf<String>()
    private val attempts = mutableMapOf<String, Int>()
    private val requests = mutableListOf<RequestRecord>()
    private var lastError: String? = null

    private val candidates = ThreadLocal<MutableList<String>>()
    val beforeSend =
        PostHogBeforeSend { event ->
            event.uuid?.let { candidates.get()?.add(it.toString()) }
            event
        }

    fun <T> track(action: () -> T): T {
        val ids = mutableListOf<String>()
        candidates.set(ids)
        try {
            return action()
        } finally {
            // Stateful capture builds an intermediate event, then the stateless base builds
            // the queued event. Only the final beforeSend UUID belongs to the queue.
            synchronized(this) { ids.lastOrNull()?.let { captured.add(it) } }
            candidates.remove()
        }
    }

    @Synchronized fun capturedIds(): Set<String> = captured.toSet()

    @Synchronized fun sentCount(): Int = sent.size

    @Synchronized fun pendingCount(): Int = (captured - sent).size

    @Synchronized fun record(
        bytes: ByteArray,
        encoding: String?,
        status: Int,
        started: Long,
    ) {
        val decoded = if (encoding == "gzip") GZIPInputStream(bytes.inputStream()).use { it.readBytes() } else bytes
        val ids =
            JsonParser.parseString(decoded.toString(Charsets.UTF_8)).asJsonObject["batch"].asJsonArray
                .map { it.asJsonObject["uuid"].asString }
        captured.addAll(ids)
        val attempt = ids.maxOfOrNull { attempts[it] ?: 0 } ?: 0
        ids.forEach { attempts[it] = (attempts[it] ?: 0) + 1 }
        requests.add(RequestRecord(started, status, attempt, ids.size, ids))
        if (status in 200..299) sent.addAll(ids) else lastError = "HTTP $status"
    }

    @Synchronized fun state(): Map<String, Any?> =
        mapOf(
            // Unacknowledged events are not a queue-depth API: terminal drops remain unresolved.
            "pending_events" to pendingCount(),
            "total_events_captured" to captured.size,
            "total_events_sent" to sent.size,
            "total_retries" to requests.count { it.retry_attempt > 0 },
            "last_error" to lastError,
            "requests_made" to requests.toList(),
        )
}

private class Session(val target: String, val storage: File, private val closeTimeoutMs: Long) {
    val observation = Observation()
    lateinit var client: SdkClient

    private val lock = ReentrantLock()
    private val settled = lock.newCondition()
    private var closed = false
    private var inFlight = 0
    private val cleanup =
        FutureTask<Unit> {
            lock.withLock {
                while (inFlight > 0) settled.await()
            }
            client.close()
            check(storage.deleteRecursively()) { "Could not remove retired session storage" }
        }

    fun requireActive() =
        lock.withLock {
            check(!closed) { "Session is retired; call /init after cleanup" }
        }

    fun begin(): Boolean =
        lock.withLock {
            if (closed) {
                false
            } else {
                inFlight++
                true
            }
        }

    fun end() =
        lock.withLock {
            inFlight--
            settled.signalAll()
        }

    fun close() {
        lock.withLock {
            if (!closed) {
                closed = true
                // Cleanup outlives the bounded caller wait and runs once, off the ingress thread.
                thread(name = "posthog-compliance-cleanup", isDaemon = true) { cleanup.run() }
            }
        }
        try {
            cleanup.get(closeTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            throw IllegalStateException("Previous session cleanup is still pending", e)
        }
    }
}

fun Application.complianceRoutes(
    profile: SdkProfile,
    port: Int,
    storageRoot: File,
    sessionCloseTimeoutMs: Long = 15_000,
) {
    install(IgnoreTrailingSlash)
    val gson = Gson().newBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).serializeNulls().create()
    val actions = Mutex()
    val sessions = ConcurrentHashMap<String, Session>()
    var active: Session? = null
    val proxy = passiveProxyClient()
    val hopHeaders = setOf("host", "connection", "content-length", "transfer-encoding", "keep-alive", "upgrade", "proxy-connection")

    routing {
        get("/health") {
            call.respondText(
                gson.toJson(
                    mapOf(
                        "sdk_name" to profile.name,
                        "sdk_version" to profile.version,
                        "adapter_version" to "1.0.0",
                        "supports_parallel" to false,
                        "capabilities" to listOf("capture_v0", "encoding_gzip"),
                    ),
                ),
                ContentType.Application.Json,
            )
        }
        route("/ingress/{id}/{path...}") {
            handle {
                val session = sessions[call.parameters["id"]]
                if (session == null || !session.begin()) {
                    call.respondText("Session closed", status = HttpStatusCode.Gone)
                    return@handle
                }
                try {
                    val prefix = "/ingress/${call.parameters["id"]}"
                    val path = call.request.uri.removePrefix(prefix)
                    val bytes = withContext(Dispatchers.IO) { call.receiveChannel().toInputStream().readBytes() }
                    val started = System.currentTimeMillis()
                    val request = Request.Builder().url(session.target.trimEnd('/') + path)
                    call.request.headers.forEach { key, values ->
                        if (key.lowercase() !in hopHeaders) values.forEach { request.addHeader(key, it) }
                    }
                    request.method(
                        call.request.httpMethod.value,
                        if (call.request.httpMethod.value in listOf("GET", "HEAD")) null else bytes.toRequestBody(),
                    )
                    withContext(Dispatchers.IO) {
                        proxy.newCall(request.build()).execute().use { response ->
                            if (path.substringBefore('?') == "/batch") {
                                session.observation.record(bytes, call.request.headers["Content-Encoding"], response.code, started)
                            }
                            response.headers.forEach { (key, value) ->
                                if (key.lowercase() !in hopHeaders) call.response.headers.append(key, value, safeOnly = false)
                            }
                            call.respondBytes(response.body?.bytes() ?: byteArrayOf(), status = HttpStatusCode.fromValue(response.code))
                        }
                    }
                } finally {
                    session.end()
                }
            }
        }
        post("/{action}") {
            actions.withLock {
                withContext(Dispatchers.IO) {
                    try {
                        val result: Any =
                            when (call.parameters["action"]) {
                                "init" -> {
                                    active?.close()
                                    sessions.clear()
                                    val req = gson.fromJson(call.receive<String>(), InitRequest::class.java)
                                    val id = UUID.randomUUID().toString()
                                    val session = Session(req.host, File(storageRoot, id).apply { mkdirs() }, sessionCloseTimeoutMs)
                                    sessions[id] = session
                                    session.client =
                                        profile.create(
                                            req.copy(host = "http://127.0.0.1:$port/ingress/$id"),
                                            session.storage,
                                            session.observation,
                                        )
                                    active = session
                                    mapOf("success" to true)
                                }
                                "capture" -> {
                                    val session = checkNotNull(active) { "SDK not initialized" }
                                    session.requireActive()
                                    val before = session.observation.capturedIds()
                                    session.client.capture(gson.fromJson(call.receive<String>(), CaptureRequest::class.java))
                                    val uuid = (session.observation.capturedIds() - before).singleOrNull()
                                    checkNotNull(uuid) { "SDK did not synchronously expose a captured UUID" }
                                    mapOf("success" to true, "uuid" to uuid)
                                }
                                "get_feature_flag" -> {
                                    val session = checkNotNull(active) { "SDK not initialized" }
                                    session.requireActive()
                                    val value = session.client.flag(gson.fromJson(call.receive<String>(), FlagRequest::class.java))
                                    mapOf("success" to true, "value" to value)
                                }
                                "flush" -> {
                                    val session = checkNotNull(active) { "SDK not initialized" }
                                    session.requireActive()
                                    val before = session.observation.sentCount()
                                    session.client.flush()
                                    val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
                                    while (session.observation.pendingCount() > 0 && System.nanoTime() < deadline) Thread.sleep(25)
                                    mapOf(
                                        "success" to (session.observation.pendingCount() == 0),
                                        "events_flushed" to (session.observation.sentCount() - before),
                                    )
                                }
                                "reset" -> {
                                    active?.close()
                                    sessions.clear()
                                    active = null
                                    mapOf("success" to true)
                                }
                                else -> error("Unknown action")
                            }
                        call.respondText(gson.toJson(result), ContentType.Application.Json)
                    } catch (e: Exception) {
                        call.respondText(
                            gson.toJson(mapOf("success" to false, "error" to e.toString())),
                            ContentType.Application.Json,
                            HttpStatusCode.BadRequest,
                        )
                    }
                }
            }
        }
        get("/state") {
            actions.withLock {
                call.respondText(gson.toJson(active?.observation?.state() ?: Observation().state()), ContentType.Application.Json)
            }
        }
    }
}

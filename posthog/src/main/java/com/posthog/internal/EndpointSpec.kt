package com.posthog.internal

import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import com.posthog.PostHogInternal
import com.posthog.logs.PostHogLogRecord
import java.io.InputStream
import java.io.OutputStream

/**
 * Per-endpoint specification consumed by [PostHogQueue]. Carries
 * everything that differs between PostHog endpoints (`/i/v1/analytics/events`,
 * `/snapshot`, and any future endpoint): the codec, the send call, the retry
 * policy, and the per-endpoint runtime knobs.
 *
 * `send` returns a [SendResult] naming the [recordId]s the server asked to
 * retry; an empty set means every record is done (delivered or dropped).
 * Endpoints without per-record results return an empty result and leave
 * [recordId] null.
 *
 * The byte limits are measured on the stored record files. [maxBatchBytes] is
 * a soft limit on one request: a record that doesn't fit starts the next
 * request, a record larger than the limit goes alone, and the queue flushes as
 * soon as that many bytes are queued. [maxQueueBytes] is a FIFO limit on the
 * queued files on top of [maxQueueSize]; the newest record is always kept.
 * Null means no limit.
 *
 * The queue itself stays record-type-agnostic; everything that varies
 * between endpoints lives here.
 */
@PostHogInternal
public class EndpointSpec<Record> internal constructor(
    internal val recordsLabel: String,
    internal val storagePrefix: String?,
    internal val initialCap: (PostHogConfig) -> Int,
    internal val initialFlushAt: (PostHogConfig) -> Int,
    internal val maxQueueSize: (PostHogConfig) -> Int,
    internal val flushIntervalSeconds: (PostHogConfig) -> Int,
    internal val encode: (Record, OutputStream) -> Unit,
    internal val decode: (InputStream) -> Record?,
    internal val describe: (Record) -> String,
    internal val send: (List<Record>) -> SendResult,
    internal val isRetriableStatusCode: (Int) -> Boolean,
    internal val isFatalRecord: (Record) -> Boolean = { false },
    internal val canBatchTogether: ((Record, Record) -> Boolean)? = null,
    internal val recordId: ((Record) -> String?)? = null,
    internal val maxBatchBytes: Long? = null,
    internal val maxQueueBytes: Long? = null,
) {
    public companion object {
        @JvmStatic
        public fun batch(
            config: PostHogConfig,
            api: PostHogApi,
            storagePrefix: String?,
        ): EndpointSpec<PostHogEvent> =
            EndpointSpec(
                recordsLabel = "events",
                storagePrefix = storagePrefix,
                initialCap = { it.maxBatchSize },
                initialFlushAt = { it.flushAt },
                maxQueueSize = { it.maxQueueSize },
                flushIntervalSeconds = { it.flushIntervalSeconds },
                encode = { event, stream ->
                    config.serializer.serialize(event, stream.writer().buffered())
                },
                decode = { stream ->
                    config.serializer.deserialize<PostHogEvent?>(stream.reader().buffered())
                },
                describe = { event -> "Event ${event.event}" },
                send = { events -> api.captureV1(events) },
                isRetriableStatusCode = ::isCaptureV1RetriableStatusCode,
                isFatalRecord = { it.isFatalExceptionEvent() },
                recordId = { event -> event.uuid?.toString() },
                maxBatchBytes = CAPTURE_V1_MAX_BATCH_BYTES,
            )

        /**
         * Returns the [EndpointSpec] for the capture v1 AI endpoint (`/i/v1/ai/events`), used by
         * `captureAi`. Same codec, results and retry policy as [batch], on its own queue: AI events
         * can be megabytes, so the queue is capped by bytes as well as count, and an event over
         * [PostHogConfig.captureAiMaxEventBytes] (plus the stored envelope) is dropped when queued.
         */
        @JvmStatic
        public fun ai(
            config: PostHogConfig,
            api: PostHogApi,
            storagePrefix: String?,
        ): EndpointSpec<PostHogEvent> =
            EndpointSpec(
                recordsLabel = "AI events",
                storagePrefix = storagePrefix,
                initialCap = { it.maxBatchSize },
                initialFlushAt = { it.flushAt },
                maxQueueSize = { it.effectiveCaptureAiMaxQueueSize },
                flushIntervalSeconds = { it.flushIntervalSeconds },
                encode = { event, stream ->
                    config.serializer.serialize(event, stream.writer().buffered())
                },
                decode = { stream ->
                    config.serializer.deserialize<PostHogEvent?>(stream.reader().buffered())
                },
                // Name only: AI properties can hold prompts and media.
                describe = { event -> "AI event ${event.event}" },
                send = { events -> api.captureAi(events) },
                isRetriableStatusCode = ::isCaptureV1RetriableStatusCode,
                recordId = { event -> event.uuid?.toString() },
                maxBatchBytes = CAPTURE_V1_MAX_BATCH_BYTES,
                maxQueueBytes = AI_MAX_QUEUE_BYTES,
            )

        @JvmStatic
        public fun snapshot(
            config: PostHogConfig,
            api: PostHogApi,
            storagePrefix: String?,
        ): EndpointSpec<PostHogEvent> =
            EndpointSpec(
                recordsLabel = "snapshots",
                storagePrefix = storagePrefix,
                initialCap = { it.maxBatchSize },
                initialFlushAt = { it.flushAt },
                maxQueueSize = { it.maxQueueSize },
                flushIntervalSeconds = { it.flushIntervalSeconds },
                encode = { event, stream ->
                    config.serializer.serialize(event, stream.writer().buffered())
                },
                decode = { stream ->
                    config.serializer.deserialize<PostHogEvent?>(stream.reader().buffered())
                },
                describe = { _ -> "snapshot" },
                canBatchTogether = { first, next ->
                    // Replay ingestion attributes the entire request to the first snapshot.
                    first.distinctId == next.distinctId &&
                        (first.properties?.get("\$session_id") as? String) == (next.properties?.get("\$session_id") as? String)
                },
                send = { events ->
                    api.snapshot(events)
                    SendResult()
                },
                isRetriableStatusCode = ::isSnapshotRetriableStatusCode,
                isFatalRecord = { it.isFatalExceptionEvent() },
            )

        /**
         * Returns the [EndpointSpec] for PostHog's logs ingestion endpoint
         * (`/i/v1/logs`, OTLP/JSON). Resource attributes (`service.name`,
         * `service.version`, `deployment.environment`, `os.*`, user-supplied
         * `resourceAttributes`) are captured from `config.logs` and
         * `config.context` once at factory time; post-setup mutations to
         * those configs are not honored. Batch knobs are read live from
         * `config.logs` on every flush.
         */
        @JvmStatic
        public fun logs(
            config: PostHogConfig,
            api: PostHogApi,
            storagePrefix: String?,
        ): EndpointSpec<PostHogLogRecord> {
            val resourceAttrs: Map<String, Any> by lazy { buildLogsResourceAttributes(config) }
            return EndpointSpec(
                recordsLabel = "logs",
                storagePrefix = storagePrefix,
                initialCap = { it.logs.maxBatchSize },
                initialFlushAt = { it.logs.flushAt },
                maxQueueSize = { it.logs.maxBufferSize },
                flushIntervalSeconds = { it.logs.flushIntervalSeconds },
                encode = { record, stream ->
                    config.serializer.serialize(record.toStorageMap(), stream.writer().buffered())
                },
                decode = { stream ->
                    val map: Map<String, Any>? = config.serializer.deserialize(stream.reader().buffered())
                    map?.let { PostHogLogRecord.fromStorageMap(it) }
                },
                describe = { _ -> "log" },
                send = { records ->
                    api.sendLogs(records, resourceAttrs)
                    SendResult()
                },
                isRetriableStatusCode = ::isLogsRetriableStatusCode,
            )
        }
    }
}

/**
 * What an endpoint's `send` reports back to the queue: the record ids the
 * server asked to retry, empty when every record is done, and the
 * `Retry-After` the server sent with that answer.
 */
internal class SendResult(
    val retryIds: Set<String> = emptySet(),
    val retryAfterSeconds: Int? = null,
)

/**
 * Capture v1 retry policy: 408 and the listed 5xx. Everything else, including 429 and 3xx, is
 * terminal; 413 is handled by the queue's batch halving.
 */
internal fun isCaptureV1RetriableStatusCode(code: Int): Boolean = code == 408 || code in setOf(500, 502, 503, 504)

/** Soft request size for the capture v1 analytics and AI endpoints, well under their body limits. */
internal const val CAPTURE_V1_MAX_BATCH_BYTES: Long = 5L * 1024 * 1024

/** Disk budget for queued AI events; the oldest are dropped first. */
internal const val AI_MAX_QUEUE_BYTES: Long = 50L * 1024 * 1024

/**
 * The stored AI event also holds its envelope (name, ids, timestamp), so this much is allowed on
 * top of the properties limit, which PostHog measures on the properties alone.
 */
internal const val AI_EVENT_ENVELOPE_BYTES: Long = 64L * 1024

/** [PostHogConfig.captureAiMaxQueueSize], or its default when it isn't positive. */
internal val PostHogConfig.effectiveCaptureAiMaxQueueSize: Int
    get() = if (captureAiMaxQueueSize > 0) captureAiMaxQueueSize else PostHogConfig.DEFAULT_CAPTURE_AI_MAX_QUEUE_SIZE

/** [PostHogConfig.captureAiTimeout], or its default when it isn't positive. */
internal val PostHogConfig.effectiveCaptureAiTimeoutSeconds: Int
    get() = if (captureAiTimeout > 0) captureAiTimeout else PostHogConfig.DEFAULT_CAPTURE_AI_TIMEOUT_SECONDS

/**
 * Bytes [event] takes serialized as the queue stores it, before any [PostHogConfig.encryption].
 * Counted as it's written, so the event isn't held in memory a second time.
 */
internal fun PostHogConfig.serializedSize(event: PostHogEvent): Long {
    var count = 0L
    val counter =
        object : OutputStream() {
            override fun write(b: Int) {
                count++
            }

            override fun write(
                b: ByteArray,
                off: Int,
                len: Int,
            ) {
                count += len
            }
        }
    serializer.serialize(event, counter.writer().buffered())
    return count
}

/**
 * Size limit for a serialized AI event, before any [PostHogConfig.encryption]: [PostHogConfig.captureAiMaxEventBytes], capped at PostHog's
 * 8 MiB limit, plus the envelope allowance.
 */
internal val PostHogConfig.captureAiMaxStoredEventBytes: Long
    get() {
        val limit = PostHogConfig.DEFAULT_CAPTURE_AI_MAX_EVENT_BYTES
        val bytes = if (captureAiMaxEventBytes > 0) minOf(captureAiMaxEventBytes, limit) else limit
        return bytes + AI_EVENT_ENVELOPE_BYTES
    }

/** `/snapshot` (replay) retry policy: 408, 429, the listed 5xx, plus 3xx redirects. */
internal fun isSnapshotRetriableStatusCode(code: Int): Boolean = code in setOf(408, 429, 500, 502, 503, 504) || code in 300..399

/** `/i/v1/logs` retry policy: 408, 429, every 5xx, plus 3xx redirects. */
internal fun isLogsRetriableStatusCode(code: Int): Boolean = code == 408 || code == 429 || code in 300..399 || code in 500..599

/**
 * Captures the resource attributes for the logs endpoint at SDK setup.
 *
 * Layering order (lowest priority first, later wins on collision):
 * 1. User-supplied `config.logs.resourceAttributes`
 * 2. `os.name` / `os.version` from [PostHogContext]'s static context
 * 3. `service.name` / `service.version` from [PostHogLogsConfig], falling
 *    back to `$app_namespace` / `$app_version` from [PostHogContext]
 * 4. `deployment.environment` when set on the logs config
 *
 * SDK-managed `telemetry.sdk.*` keys are layered separately inside
 * `PostHogLogsOTLP.buildPayload` and beat everything here.
 */
private fun buildLogsResourceAttributes(config: PostHogConfig): Map<String, Any> {
    val logs = config.logs
    val staticContext = config.context?.getStaticContext().orEmpty()
    val merged = LinkedHashMap<String, Any>()

    merged.putAll(logs.resourceAttributes)

    (staticContext["\$os_name"] as? String)?.let { merged["os.name"] = it }
    (staticContext["\$os_version"] as? String)?.let { merged["os.version"] = it }

    // OTel spec requires service.name; default to "unknown_service" when no
    // user value or context override is available.
    val serviceName =
        logs.serviceName
            ?: staticContext["\$app_namespace"] as? String
            ?: "unknown_service"
    merged["service.name"] = serviceName
    val serviceVersion = logs.serviceVersion ?: staticContext["\$app_version"] as? String
    serviceVersion?.let { merged["service.version"] = it }
    logs.environment?.let { merged["deployment.environment"] = it }

    return merged
}

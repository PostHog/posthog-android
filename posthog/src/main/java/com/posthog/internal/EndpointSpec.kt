package com.posthog.internal

import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import com.posthog.PostHogInternal
import com.posthog.logs.PostHogLogRecord
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Per-endpoint specification consumed by [PostHogQueue]. Carries
 * everything that differs between PostHog endpoints (`/batch`, `/snapshot`,
 * and any future endpoint): the codec, the send call, the retry policy,
 * and the per-endpoint runtime knobs.
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
    internal val send: (List<Record>) -> Unit,
    internal val isRetriableStatusCode: (Int) -> Boolean,
    internal val isFatalRecord: (Record) -> Boolean = { false },
    internal val recordUuid: (Record) -> UUID? = { null },
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
                send = { events -> api.batch(events) },
                isRetriableStatusCode = ::isEventsRetriableStatusCode,
                isFatalRecord = { it.isFatalExceptionEvent() },
                recordUuid = { it.uuid },
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
                send = { events -> api.snapshot(events) },
                isRetriableStatusCode = ::isEventsRetriableStatusCode,
                isFatalRecord = { it.isFatalExceptionEvent() },
                recordUuid = { it.uuid },
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
                send = { records -> api.sendLogs(records, resourceAttrs) },
                isRetriableStatusCode = ::isLogsRetriableStatusCode,
            )
        }
    }
}

internal val DEFAULT_EVENTS_RETRYABLE_STATUS_CODES: Set<Int> = setOf(429, 500, 502, 503, 504)

internal fun isEventsRetriableStatusCode(code: Int): Boolean = code in DEFAULT_EVENTS_RETRYABLE_STATUS_CODES

internal fun isLogsRetriableStatusCode(code: Int): Boolean = code == 408 || code == 429 || code in 500..599

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

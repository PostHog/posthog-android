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
         * (`/i/v1/logs`, OTLP/JSON). Pass the result to a
         * [com.posthog.internal.PostHogQueue] to enqueue and flush log
         * records.
         *
         * `resourceAttributes` is captured by value; supply the
         * once-per-setup snapshot of any user-configurable resource keys
         * (e.g. `service.name`, `service.version`, `deployment.environment`)
         * here. SDK-managed `telemetry.sdk.*` keys are appended automatically
         * and override any user-supplied values for those keys.
         */
        internal fun logs(
            config: PostHogConfig,
            api: PostHogApi,
            storagePrefix: String?,
            resourceAttributes: Map<String, Any> = emptyMap(),
        ): EndpointSpec<PostHogLogRecord> =
            EndpointSpec(
                recordsLabel = "logs",
                storagePrefix = storagePrefix,
                initialCap = { it.maxBatchSize },
                initialFlushAt = { it.flushAt },
                maxQueueSize = { it.maxQueueSize },
                flushIntervalSeconds = { it.flushIntervalSeconds },
                encode = { record, stream ->
                    config.serializer.serialize(record.toStorageMap(), stream.writer().buffered())
                },
                decode = { stream ->
                    val map: Map<String, Any>? = config.serializer.deserialize(stream.reader().buffered())
                    map?.let { PostHogLogRecord.fromStorageMap(it) }
                },
                describe = { _ -> "log" },
                send = { records -> api.sendLogs(records, resourceAttributes) },
                isRetriableStatusCode = ::isLogsRetriableStatusCode,
            )
    }
}

internal val DEFAULT_EVENTS_RETRYABLE_STATUS_CODES: Set<Int> = setOf(429, 500, 502, 503, 504)

internal fun isEventsRetriableStatusCode(code: Int): Boolean = code in DEFAULT_EVENTS_RETRYABLE_STATUS_CODES

internal fun isLogsRetriableStatusCode(code: Int): Boolean = code == 408 || code == 429 || code in 500..599

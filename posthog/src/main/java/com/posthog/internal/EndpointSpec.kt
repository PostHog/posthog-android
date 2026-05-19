package com.posthog.internal

import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import com.posthog.PostHogInternal
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
    internal val name: String,
    internal val storagePrefix: String?,
    internal val initialCap: Int,
    internal val initialFlushAt: Int,
    internal val maxQueueSize: Int,
    internal val flushIntervalSeconds: Int,
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
                name = "events",
                storagePrefix = storagePrefix,
                initialCap = config.maxBatchSize,
                initialFlushAt = config.flushAt,
                maxQueueSize = config.maxQueueSize,
                flushIntervalSeconds = config.flushIntervalSeconds,
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
                name = "snapshots",
                storagePrefix = storagePrefix,
                initialCap = config.maxBatchSize,
                initialFlushAt = config.flushAt,
                maxQueueSize = config.maxQueueSize,
                flushIntervalSeconds = config.flushIntervalSeconds,
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
    }
}

internal val DEFAULT_EVENTS_RETRYABLE_STATUS_CODES: Set<Int> = setOf(429, 500, 502, 503, 504)

internal fun isEventsRetriableStatusCode(code: Int): Boolean = code in DEFAULT_EVENTS_RETRYABLE_STATUS_CODES

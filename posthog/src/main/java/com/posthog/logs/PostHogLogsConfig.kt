package com.posthog.logs

import com.posthog.PostHogConfig
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Configuration for PostHog's logs ingestion.
 *
 * Mutate fields on the [PostHogConfig.logs] property **before** calling
 * `PostHog.setup(config)` — most knobs are snapshotted at setup and later
 * changes are ignored. Per-field mutability is documented on each property.
 *
 * ### Kotlin
 *
 * ```kotlin
 * val config = PostHogAndroidConfig(apiKey).apply {
 *     logs.serviceName = "checkout-android"
 *     logs.environment = "production"
 *     logs.resourceAttributes = mapOf("region" to "us-east-1")
 *
 *     // Redact PII before records leave the device:
 *     logs.addBeforeSend { record ->
 *         if (record.body.contains("@")) {
 *             record.copy(body = record.body.replace(EMAIL_REGEX, "[redacted]"))
 *         } else {
 *             record
 *         }
 *     }
 * }
 * PostHogAndroid.setup(this, config)
 * ```
 *
 * ### Java
 *
 * ```java
 * PostHogAndroidConfig config = new PostHogAndroidConfig(apiKey);
 * config.getLogs().setServiceName("checkout-android");
 * config.getLogs().setEnvironment("production");
 * config.getLogs().addBeforeSend(record ->
 *     record.getBody().contains("@") ? null : record
 * );
 * PostHogAndroid.setup(context, config);
 * ```
 */
public class PostHogLogsConfig {
    /**
     * OpenTelemetry `service.name` resource attribute identifying the app
     * sending logs.
     *
     * When `null` (the default), falls back to the host app's package id
     * from `PostHogContext.$app_namespace`, then to `"unknown_service"` per
     * the OTel spec. Override to disambiguate multiple apps reporting into
     * the same project, or to match a value used by other observability
     * tooling.
     *
     * Snapshotted at SDK setup; mutations after `PostHog.setup(...)` are
     * not honored.
     *
     * ```kotlin
     * config.logs.serviceName = "checkout-android"
     * ```
     */
    public var serviceName: String? = null

    /**
     * OpenTelemetry `service.version` resource attribute, typically the
     * app's release version.
     *
     * When `null` (the default), falls back to the host app's version from
     * `PostHogContext.$app_version` (usually `BuildConfig.VERSION_NAME`).
     *
     * Snapshotted at SDK setup; mutations after `PostHog.setup(...)` are
     * not honored.
     *
     * ```kotlin
     * config.logs.serviceVersion = "1.4.2"
     * ```
     */
    public var serviceVersion: String? = null

    /**
     * OpenTelemetry `deployment.environment` resource attribute. Common
     * values: `"production"`, `"staging"`, `"development"`. Omitted from
     * the payload entirely when `null`.
     *
     * Snapshotted at SDK setup; mutations after `PostHog.setup(...)` are
     * not honored.
     *
     * ```kotlin
     * config.logs.environment = if (BuildConfig.DEBUG) "development" else "production"
     * ```
     */
    public var environment: String? = null

    /**
     * Additional OpenTelemetry resource attributes attached to every batch.
     *
     * SDK-managed keys (`telemetry.sdk.*`, `os.*`, `service.*`,
     * `deployment.environment`) win on collision so you can't accidentally
     * shadow them.
     *
     * Snapshotted at SDK setup; mutations after `PostHog.setup(...)` are
     * not honored.
     *
     * ```kotlin
     * config.logs.resourceAttributes = mapOf(
     *     "region" to "us-east-1",
     *     "host.name" to Build.MODEL,
     * )
     * ```
     */
    public var resourceAttributes: Map<String, Any> = emptyMap()

    /**
     * How often the queue checks for records to flush, in seconds.
     *
     * Re-read on every flush cycle — safe to change at runtime if needed.
     */
    public var flushIntervalSeconds: Int = PostHogConfig.DEFAULT_FLUSH_INTERVAL_SECONDS

    /**
     * Threshold at which the queue triggers a flush automatically.
     *
     * Setting this lower ships records sooner at the cost of more frequent
     * network requests; higher batches them up.
     *
     * Read once at queue construction; mutations after `PostHog.setup(...)`
     * are not honored.
     */
    public var flushAt: Int = PostHogConfig.DEFAULT_FLUSH_AT

    /**
     * Initial maximum number of records sent in a single request.
     *
     * May be reduced automatically under server backpressure (HTTP 413).
     * Read once at queue construction; mutations after `PostHog.setup(...)`
     * are not honored.
     */
    public var maxBatchSize: Int = PostHogConfig.DEFAULT_MAX_BATCH_SIZE

    /**
     * Maximum number of records held on disk before the oldest is dropped
     * (FIFO).
     *
     * Protects against unbounded growth when the device is offline for an
     * extended period. Re-read on every add — safe to change at runtime.
     */
    public var maxBufferSize: Int = PostHogConfig.DEFAULT_MAX_QUEUE_SIZE

    /**
     * Maximum number of log records accepted per [rateCapWindowSeconds]
     * before subsequent records in the same window are dropped at capture
     * time.
     *
     * Caps cellular data cost from runaway logging loops (e.g. a `trace`
     * call inside a tight render loop). Set to `0` (or any non-positive
     * value) to disable the cap entirely.
     *
     * ```kotlin
     * // Allow up to 100 logs every 5 seconds:
     * config.logs.rateCapMaxLogs = 100
     * config.logs.rateCapWindowSeconds = 5
     * ```
     */
    public var rateCapMaxLogs: Int = 500

    /**
     * Tumbling window in seconds used by [rateCapMaxLogs]. The counter
     * resets when wall-clock time advances past the window.
     */
    public var rateCapWindowSeconds: Int = 10

    private val beforeSend: CopyOnWriteArrayList<PostHogBeforeSendLog> = CopyOnWriteArrayList()

    /**
     * Snapshot of the currently registered `beforeSend` hooks, in
     * registration order. Useful for diagnostics and test assertions; the
     * returned list is an immutable copy.
     */
    public val beforeSendList: List<PostHogBeforeSendLog>
        get() = beforeSend.toList()

    /**
     * Adds a `beforeSend` hook that can mutate or drop log records before
     * they reach the queue.
     *
     * Hooks compose left-to-right; returning `null` from any hook drops
     * the record and short-circuits the chain. A hook that throws is
     * treated the same as returning `null` (the record is dropped, the
     * throwable is logged via the SDK's internal debug logger). Hooks
     * run synchronously on the caller's thread — keep them fast.
     *
     * Live: added/removed hooks take effect on the next `captureLog` call.
     *
     * ```kotlin
     * // Drop trace records in production:
     * config.logs.addBeforeSend { record ->
     *     if (record.level == PostHogLogSeverity.TRACE) null else record
     * }
     *
     * // Strip a known PII field:
     * config.logs.addBeforeSend { record ->
     *     record.copy(attributes = record.attributes - "user.email")
     * }
     * ```
     */
    public fun addBeforeSend(hook: PostHogBeforeSendLog) {
        beforeSend.add(hook)
    }

    /**
     * Removes a previously-added `beforeSend` hook. Pass the same lambda
     * reference you originally registered.
     *
     * ```kotlin
     * val redact = PostHogBeforeSendLog { it.copy(body = "[redacted]") }
     * config.logs.addBeforeSend(redact)
     * // ...later:
     * config.logs.removeBeforeSend(redact)
     * ```
     */
    public fun removeBeforeSend(hook: PostHogBeforeSendLog) {
        beforeSend.remove(hook)
    }

    /**
     * Runs all `beforeSend` hooks in order. Returns the final record, or
     * `null` if any hook returned `null`, threw, or if the resulting body
     * is blank. [onHookError] is invoked with the throwable for any hook
     * that throws — matches the events pipeline's behavior.
     */
    internal fun runBeforeSend(
        record: PostHogLogRecord,
        onHookError: ((Throwable) -> Unit)? = null,
    ): PostHogLogRecord? {
        if (beforeSend.isEmpty()) {
            return if (record.body.isBlank()) null else record
        }
        var current: PostHogLogRecord = record
        for (hook in beforeSend) {
            current = try {
                hook.run(current) ?: return null
            } catch (e: Throwable) {
                onHookError?.invoke(e)
                return null
            }
        }
        if (current.body.isBlank()) return null
        return current
    }
}

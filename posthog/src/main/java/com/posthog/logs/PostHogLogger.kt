package com.posthog.logs

/**
 * Captures application log records and forwards them to PostHog's logs
 * ingestion endpoint.
 *
 * Obtain via [com.posthog.PostHog.logger] (static, recommended) or
 * [com.posthog.PostHogInterface.logger] when you hold a live SDK instance.
 *
 * ### Kotlin usage
 *
 * ```kotlin
 * import com.posthog.PostHog
 * import com.posthog.logs.PostHogLogSeverity
 *
 * // Severity-specific shortcuts:
 * PostHog.logger.info("checkout opened")
 * PostHog.logger.error(
 *     "payment failed",
 *     attributes = mapOf("amount_cents" to 1999, "currency" to "USD"),
 * )
 *
 * // Generic entry point with a runtime severity:
 * PostHog.logger.log("rendered cart", severity = PostHogLogSeverity.DEBUG)
 * ```
 *
 * ### Java usage
 *
 * ```java
 * import com.posthog.PostHog;
 * import com.posthog.logs.PostHogLogSeverity;
 *
 * import java.util.Map;
 *
 * PostHog.Companion.getLogger().info("checkout opened");
 * PostHog.Companion.getLogger().error(
 *     "payment failed",
 *     Map.of("amount_cents", 1999, "currency", "USD")
 * );
 * PostHog.Companion.getLogger().log("rendered cart", PostHogLogSeverity.DEBUG, null);
 * ```
 *
 * ### Attribute values
 *
 * Attribute values must be JSON-serializable: `String`, `Number`, `Boolean`,
 * `Date`, or lists / maps composed of those types. Other values are coerced
 * to their `toString()` on the wire — keep that in mind for custom classes.
 *
 * ### Drop / redact records
 *
 * Register a [PostHogBeforeSendLog] via
 * [PostHogLogsConfig.addBeforeSend] to drop or redact records before they
 * are enqueued.
 *
 * @see PostHogLogSeverity
 * @see PostHogLogsConfig
 * @see PostHogBeforeSendLog
 */
public class PostHogLogger internal constructor(
    private val capture: (String, PostHogLogSeverity, Map<String, Any>?) -> Unit,
) {
    /**
     * Captures a log record at the given severity.
     *
     * Use the severity-specific shortcuts ([trace], [debug], [info], [warn],
     * [error], [fatal]) when the severity is known at the call site; this
     * generic entry point exists for cases where the severity is a runtime
     * value (e.g. mapping a third-party logging framework's level into
     * PostHog).
     *
     * ```kotlin
     * val level: PostHogLogSeverity = mapFromTimber(priority)
     * PostHog.logger.log(message, severity = level, attributes = mapOf("tag" to tag))
     * ```
     *
     * @param message The log message body. Blank messages are dropped.
     * @param severity The log severity. Defaults to [PostHogLogSeverity.INFO].
     * @param attributes Optional structured attributes attached to the record.
     *   Values must be JSON-serializable (`String`, `Number`, `Boolean`,
     *   `Date`, lists or maps of the same).
     */
    public fun log(
        message: String,
        severity: PostHogLogSeverity = PostHogLogSeverity.INFO,
        attributes: Map<String, Any>? = null,
    ) {
        capture(message, severity, attributes)
    }

    /**
     * Captures a finest-grained trace-level record. Usually only enabled
     * while diagnosing — high-volume; will trip the
     * [PostHogLogsConfig.rateCapMaxLogs] cap quickly in production.
     *
     * ```kotlin
     * PostHog.logger.trace("entered renderFrame")
     * ```
     */
    public fun trace(
        message: String,
        attributes: Map<String, Any>? = null,
    ): Unit = log(message, PostHogLogSeverity.TRACE, attributes)

    /**
     * Captures a debug-level record. Diagnostic detail useful during
     * development.
     *
     * ```kotlin
     * PostHog.logger.debug("cache miss for $key")
     * ```
     */
    public fun debug(
        message: String,
        attributes: Map<String, Any>? = null,
    ): Unit = log(message, PostHogLogSeverity.DEBUG, attributes)

    /**
     * Captures an info-level record. Default level for regular runtime
     * events.
     *
     * ```kotlin
     * PostHog.logger.info("user signed in", mapOf("method" to "google"))
     * ```
     */
    public fun info(
        message: String,
        attributes: Map<String, Any>? = null,
    ): Unit = log(message, PostHogLogSeverity.INFO, attributes)

    /**
     * Captures a warn-level record. Something unexpected happened but the
     * operation continued.
     *
     * ```kotlin
     * PostHog.logger.warn("retrying upload", mapOf("attempt" to 2))
     * ```
     */
    public fun warn(
        message: String,
        attributes: Map<String, Any>? = null,
    ): Unit = log(message, PostHogLogSeverity.WARN, attributes)

    /**
     * Captures an error-level record. An operation failed; the app may
     * continue.
     *
     * ```kotlin
     * PostHog.logger.error("checkout failed", mapOf("code" to "PAY_3001"))
     * ```
     */
    public fun error(
        message: String,
        attributes: Map<String, Any>? = null,
    ): Unit = log(message, PostHogLogSeverity.ERROR, attributes)

    /**
     * Captures a fatal-level record. An unrecoverable failure; the app
     * likely cannot continue. Logged records still ship asynchronously,
     * so consider calling [com.posthog.PostHog.flush] before the process
     * exits.
     *
     * ```kotlin
     * PostHog.logger.fatal("database corrupted")
     * PostHog.flush()
     * ```
     */
    public fun fatal(
        message: String,
        attributes: Map<String, Any>? = null,
    ): Unit = log(message, PostHogLogSeverity.FATAL, attributes)

    internal companion object {
        // No-op singleton used by [PostHogInterface]'s default `logger`
        // getter. The default is compiled into `PostHogInterface$DefaultImpls`,
        // which lives in this module — so the call to NO_OP is intra-module
        // and `internal` access is sufficient. Not part of the public API
        // (iOS doesn't expose an equivalent either).
        @JvmField
        internal val NO_OP: PostHogLogger = PostHogLogger { _, _, _ -> }
    }
}

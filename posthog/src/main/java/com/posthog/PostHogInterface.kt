package com.posthog

import com.posthog.logs.PostHogLogSeverity
import com.posthog.logs.PostHogLogger
import java.util.Date
import java.util.UUID

/**
 * The PostHog SDK entry point
 */
public interface PostHogInterface : PostHogCoreInterface {
    /**
     * Captures application log records into PostHog's logs product
     * (separate from product analytics events). Use the severity-specific
     * helpers ([PostHogLogger.trace], [debug][PostHogLogger.debug],
     * [info][PostHogLogger.info], [warn][PostHogLogger.warn],
     * [error][PostHogLogger.error], [fatal][PostHogLogger.fatal]) or the
     * generic [PostHogLogger.log].
     *
     * ```kotlin
     * posthog.logger.info("checkout opened")
     * posthog.logger.error("payment failed", mapOf("code" to "PAY_3001"))
     * ```
     *
     * Not to be confused with the internal `config.logger` debug sink.
     */
    public val logger: PostHogLogger

    /**
     * Captures a single log record into PostHog's logs product, with optional
     * W3C distributed-tracing correlation.
     *
     * Prefer the [logger] facade ([logger].info, .error, …) for everyday
     * logging. Reach for this entry point when you need to correlate a log
     * with a distributed trace by attaching [traceId] / [spanId] / [traceFlags].
     *
     * ```kotlin
     * posthog.captureLog(
     *     "payment failed",
     *     severity = PostHogLogSeverity.ERROR,
     *     attributes = mapOf("code" to "PAY_3001"),
     *     traceId = "4bf92f3577b34da6a3ce929d0e0e4736",
     *     spanId = "00f067aa0ba902b7",
     *     traceFlags = 0x01,
     * )
     * ```
     *
     * @param message the log message body; blank messages are dropped
     * @param severity the log severity, defaults to [PostHogLogSeverity.INFO]
     * @param attributes optional structured attributes; values must be
     *   JSON-serializable (`String`, `Number`, `Boolean`, `Date`, lists or
     *   maps of the same)
     * @param traceId optional 32-character lowercase hex W3C trace id. The
     *   ingestion service zeroes ids that are not exactly 16 bytes.
     * @param spanId optional 16-character lowercase hex W3C span id. The
     *   ingestion service zeroes ids that are not exactly 8 bytes.
     * @param traceFlags optional W3C trace flags bitfield (bit 0 is the
     *   `sampled` flag); emitted on the wire as `flags`.
     */
    public fun captureLog(
        message: String,
        severity: PostHogLogSeverity = PostHogLogSeverity.INFO,
        attributes: Map<String, Any>? = null,
        traceId: String? = null,
        spanId: String? = null,
        traceFlags: Int? = null,
    )

    /**
     * Captures events
     * @param event the event name
     * @param distinctId the distinctId, the generated [distinctId] is used if not given
     * @param properties the custom properties
     * @param userProperties the user properties, set as a "$set" property, Docs https://posthog.com/docs/product-analytics/user-properties
     * @param userPropertiesSetOnce the user properties to set only once, set as a "$set_once" property, Docs https://posthog.com/docs/product-analytics/user-properties
     * @param groups the groups, set as a "$groups" property, Docs https://posthog.com/docs/product-analytics/group-analytics
     * @param timestamp the timestamp for the event in UTC, if not provided the current time will be used
     */
    public fun capture(
        event: String,
        distinctId: String? = null,
        properties: Map<String, Any>? = null,
        userProperties: Map<String, Any>? = null,
        userPropertiesSetOnce: Map<String, Any>? = null,
        groups: Map<String, String>? = null,
        timestamp: Date? = null,
    )

    /**
     * Captures exceptions
     * @param throwable the Throwable error
     * @param properties the custom properties
     */
    public fun captureException(
        throwable: Throwable,
        properties: Map<String, Any>? = null,
    )

    /**
     * Records a breadcrumb-style exception step. A snapshot of the recorded steps is
     * attached to every captured `$exception` event as `$exception_steps`, giving the
     * error-tracking UI a timeline of recent activity leading up to each error.
     *
     * The buffer is a rolling window scoped to the SDK instance: it persists across
     * captures and user identity changes, rotating only by byte-budget eviction
     * (see [com.posthog.errortracking.PostHogExceptionStepsConfig]).
     *
     * Recording never throws into the host app: an empty message is ignored with a
     * warning, and internal failures silently skip the step.
     *
     * Recording is synchronous: the `$timestamp` is captured at call time and the step
     * is normalized, byte-budget enforced, and buffered before this method returns, so a
     * step recorded immediately before an exception or crash is present when it is
     * captured. The work is bounded and cheap, adding negligible latency to the caller.
     *
     * @param message a non-empty description of the step
     * @param properties optional user-supplied properties (the reserved keys
     * `$message` and `$timestamp` are stripped)
     */
    public fun addExceptionStep(
        message: String,
        properties: Map<String, Any>? = null,
    )

    /**
     * Reloads the feature flags
     * @param onFeatureFlags the callback to get notified once feature flags is ready to use
     */
    public fun reloadFeatureFlags(onFeatureFlags: PostHogOnFeatureFlags? = null)

    /**
     * Returns if a feature flag is enabled, the feature flag must be a Boolean
     * Docs https://posthog.com/docs/feature-flags and https://posthog.com/docs/experiments
     * @param key the Key
     * @param defaultValue the default value if not found, false if not given
     * @param sendFeatureFlagEvent (optional) If false, we won't send an $feature_flag_called event to PostHog.
     * @return Whether the feature flag is enabled.
     */
    public fun isFeatureEnabled(
        key: String,
        defaultValue: Boolean = false,
        sendFeatureFlagEvent: Boolean? = null,
    ): Boolean

    /**
     * Returns the feature flag
     * Docs https://posthog.com/docs/feature-flags and https://posthog.com/docs/experiments
     * @param key the Key
     * @param defaultValue the default value if not found
     * @param sendFeatureFlagEvent (optional) If false, we won't send an $feature_flag_called event to PostHog.
     * @return The feature flag value, or [defaultValue] if not found.
     */
    public fun getFeatureFlag(
        key: String,
        defaultValue: Any? = null,
        sendFeatureFlagEvent: Boolean? = null,
    ): Any?

    /**
     * Returns list of all feature flag result containing both value and payload.
     * Docs https://posthog.com/docs/feature-flags and https://posthog.com/docs/experiments
     * @return The currently loaded feature flag results, or null when flags are unavailable.
     */
    public fun getAllFeatureFlags(): List<FeatureFlagResult>?

    /**
     * Returns the feature flag payload
     * Docs https://posthog.com/docs/feature-flags and https://posthog.com/docs/experiments
     * @param key the Key
     * @param defaultValue the default value if not found
     * @return The feature flag payload, or [defaultValue] if not found.
     */
    public fun getFeatureFlagPayload(
        key: String,
        defaultValue: Any? = null,
    ): Any?

    /**
     * Returns the feature flag result containing both value and payload.
     * This is the recommended way to access feature flags when you need both
     * the flag value and its payload atomically.
     *
     * Docs https://posthog.com/docs/feature-flags and https://posthog.com/docs/experiments
     * @param key The feature flag key
     * @param sendFeatureFlagEvent If false, won't send $feature_flag_called event to PostHog
     * @return FeatureFlagResult if the flag exists, null otherwise
     */
    public fun getFeatureFlagResult(
        key: String,
        sendFeatureFlagEvent: Boolean? = null,
    ): FeatureFlagResult?

    /**
     * Resets all the cached properties including the [distinctId]
     * The SDK will behave as its been setup for the first time
     */
    public fun reset()

    /**
     * Creates a group
     * Docs https://posthog.com/docs/product-analytics/group-analytics
     * @param type the Group type
     * @param key the Group key
     * @param groupProperties the Group properties, set as a "$group_set" property, Docs https://posthog.com/docs/product-analytics/group-analytics
     */
    public fun group(
        type: String,
        key: String,
        groupProperties: Map<String, Any>? = null,
    )

    /**
     * Captures a screen view event
     * @param screenTitle the screen title
     * @param properties the custom properties
     */
    public fun screen(
        screenTitle: String,
        properties: Map<String, Any>? = null,
    )

    /**
     * Creates an alias for the user
     * Docs https://posthog.com/docs/product-analytics/identify#alias-assigning-multiple-distinct-ids-to-the-same-user
     * @param alias the alias
     */
    public fun alias(alias: String)

    /**
     * Register a property to always be sent with all the following events until you call
     * [unregister] with the same key
     * PostHogPreferences.ALL_INTERNAL_KEYS are not allowed since they are internal and used by
     * the SDK only.
     * @param key the Key
     * @param value the Value
     */
    public fun register(
        key: String,
        value: Any,
    )

    /**
     * Unregisters the previously set property to be sent with all the following events
     * @param key the Key
     */
    public fun unregister(key: String)

    /**
     * Returns the registered [distinctId] property
     * @return The current distinct ID.
     */
    public fun distinctId(): String

    /**
     * Returns the stable device identifier used for device-level feature flag bucketing.
     * This ID persists across [identify] and [reset] calls, only changing on a fresh
     * app install, manual cache clearing, or OS-initiated storage cleanup.
     *
     * @return The device ID, or an empty string if not yet initialized
     */
    public fun getDeviceId(): String

    /**
     * Starts a session
     * The SDK will automatically start a session when you call [setup]
     * On Android, the SDK will automatically start a session when the app is in the foreground
     */
    public fun startSession()

    /**
     * Ends a session
     * The SDK will automatically end a session when you call [close]
     * On Android, the SDK will automatically end a session when the app is in the background
     * for at least 30 minutes
     */
    public fun endSession()

    /**
     * Returns if a session is active
     * @return Whether a session is currently active.
     */
    public fun isSessionActive(): Boolean

    /**
     * Returns if Session Replay is Active
     * Android only.
     * @return Whether session replay is currently active.
     */
    public fun isSessionReplayActive(): Boolean

    /**
     * Starts session replay.
     * This method will be NoOp if session replay is disabled in your project settings.
     *
     * Note: This method respects ingestion controls configured in your project settings.
     * If sampling is configured and the session is not sampled, recording will not start.
     * If event triggers are configured, recording will not start until a matching event is captured.
     *
     * Android only.
     *
     * @param resumeCurrent Whether to resume session replay of current session (true) or start a new session (false).
     */
    public fun startSessionReplay(resumeCurrent: Boolean = true)

    /**
     * Stops the current session replay if one is in progress.
     *
     * Android only.
     */
    public fun stopSessionReplay()

    /**
     * Returns the session Id if a session is active
     * @return The current session ID, or null if no session is active.
     */
    public fun getSessionId(): UUID?

    /**
     * Sets properties on the person profile associated with the current distinct_id.
     * Learn more about [identifying users](https://posthog.com/docs/product-analytics/identify)
     *
     * Updates user properties that are stored with the person profile in PostHog.
     * If `personProfiles` is set to `IDENTIFIED_ONLY` and no profile exists, this will create one.
     *
     * This method sends a `$set` event to PostHog.
     *
     * @param userPropertiesToSet Optional: Properties to store about the user. These will overwrite existing values.
     * @param userPropertiesToSetOnce Optional: Properties to store about the user only if not previously set.
     *   Note: For feature flag evaluations, if the same key is present in both parameters,
     *   the value from userPropertiesToSet will take precedence.
     */
    public fun setPersonProperties(
        userPropertiesToSet: Map<String, Any>? = null,
        userPropertiesToSetOnce: Map<String, Any>? = null,
    )

    /**
     * Sets person properties that will be included in feature flag evaluation requests.
     *
     * @param userProperties Dictionary of person properties to include in flag evaluation
     * @param reloadFeatureFlags Whether to automatically reload feature flags after setting properties
     */
    public fun setPersonPropertiesForFlags(
        userProperties: Map<String, Any>,
        reloadFeatureFlags: Boolean = true,
    )

    /**
     * Resets all person properties that were set for feature flag evaluation.
     * @param reloadFeatureFlags Whether to automatically reload feature flags after resetting properties
     */
    public fun resetPersonPropertiesForFlags(reloadFeatureFlags: Boolean = true)

    /**
     * Sets properties for a specific group type to include when evaluating feature flags.
     *
     * @param type The group type identifier (e.g., "organization", "team")
     * @param groupProperties Dictionary of properties to set for this group type
     * @param reloadFeatureFlags Whether to automatically reload feature flags after setting properties
     */
    public fun setGroupPropertiesForFlags(
        type: String,
        groupProperties: Map<String, Any>,
        reloadFeatureFlags: Boolean = true,
    )

    /**
     * Clears group properties for feature flag evaluation.
     *
     * @param type Optional group type to clear. If null, clears all group properties.
     * @param reloadFeatureFlags Whether to automatically reload feature flags after resetting properties
     */
    public fun resetGroupPropertiesForFlags(
        type: String? = null,
        reloadFeatureFlags: Boolean = true,
    )

    /**
     * Captures a feature view event when a user sees a feature in the UI.
     *
     * @param flag The feature flag key.
     * @param flagVariant Optional variant key.
     */
    public fun captureFeatureView(
        flag: String,
        flagVariant: String? = null,
    )

    /**
     * Captures a feature interaction event when a user interacts with a feature.
     *
     * @param flag The feature flag key
     * @param flagVariant Optional variant key.
     */
    public fun captureFeatureInteraction(
        flag: String,
        flagVariant: String? = null,
    )

    @PostHogInternal
    public fun <T : PostHogConfig> getConfig(): T?
}

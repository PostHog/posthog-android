package com.posthog.server

import com.posthog.BuildConfig
import com.posthog.PostHogBeforeSend
import com.posthog.PostHogEncryption
import com.posthog.PostHogExperimental
import com.posthog.PostHogIntegration
import com.posthog.PostHogOnFeatureFlags
import com.posthog.server.internal.PostHogFeatureFlags
import com.posthog.server.internal.PostHogMemoryQueue
import com.posthog.server.internal.PostHogServerContext
import java.net.Proxy

/**
 * Server-side SDK configuration.
 *
 * Create instances directly in Kotlin, or use [builder] from Java.
 */
public open class PostHogConfig constructor(
    /**
     * The PostHog project API key.
     */
    apiKey: String,
    /**
     * The PostHog Host
     * Defaults to https://us.i.posthog.com
     * EU Host: https://eu.i.posthog.com
     *
     */
    host: String = DEFAULT_HOST,
    /**
     * Logs the debug logs to the [logger] if enabled
     * Defaults to false
     */
    public var debug: Boolean = false,
    /**
     * Send a $feature_flag_called event when a feature flag is used automatically
     * Used by experiments
     *
     * Defaults to true
     */
    public var sendFeatureFlagEvent: Boolean = true,
    /**
     * Preload feature flags automatically
     * Docs https://posthog.com/docs/feature-flags and https://posthog.com/docs/experiments
     * Defaults to true
     */
    public var preloadFeatureFlags: Boolean = true,
    /**
     * Preload PostHog remote config automatically
     * Defaults to true
     */
    @Deprecated(
        message = "Remote config is now always enabled. This option is a no-op and will be removed in a future version.",
        level = DeprecationLevel.WARNING,
    )
    public var remoteConfig: Boolean = true,
    /**
     * Number of minimum events before they are sent over the wire
     * Defaults to 20
     */
    public var flushAt: Int = DEFAULT_FLUSH_AT,
    /**
     * Number of maximum events in memory and disk, when the maximum is exceed, the oldest
     * event is deleted and the new one takes place
     * Defaults to 1000
     */
    public var maxQueueSize: Int = DEFAULT_MAX_QUEUE_SIZE,
    /**
     * Number of maximum events in a batch call
     * Defaults to 50
     */
    public var maxBatchSize: Int = DEFAULT_MAX_BATCH_SIZE,
    /**
     * Interval in seconds for sending events over the wire
     * The lower the number, most likely more battery is used
     * Defaults to 30s
     */
    public var flushIntervalSeconds: Int = DEFAULT_FLUSH_INTERVAL_SECONDS,
    /**
     * Hook for encrypt and decrypt events
     * Devices are sandbox so likely not needed
     * Defaults to no encryption
     */
    public var encryption: PostHogEncryption? = null,
    /**
     * Hook that is called when feature flag definitions are loaded.
     * This is called immediately if local evaluation is not enabled.
     * Defaults to no callback
     */
    public var onFeatureFlags: PostHogOnFeatureFlags? = null,
    /**
     * Optional HTTP proxy for requests to the PostHog API.
     * Defaults to null.
     */
    public var proxy: Proxy? = null,
    /**
     * The maximum size of the feature flag cache in memory
     * Defaults to 1000
     */
    @PostHogExperimental
    public var featureFlagCacheSize: Int = DEFAULT_FEATURE_FLAG_CACHE_SIZE,
    /**
     * The maximum age of a feature flag cache record in memory in milliseconds
     * Defaults to 5 minutes
     */
    @PostHogExperimental
    public var featureFlagCacheMaxAgeMs: Int = DEFAULT_FEATURE_FLAG_CACHE_MAX_AGE_MS,
    /**
     * The maximum size of the feature flag called cache in memory. This cache prevents
     * duplicate $feature_flag_called events from being sent by keeping track of per-user
     * feature flag usage.
     * Defaults to 1000
     */
    public var featureFlagCalledCacheSize: Int = DEFAULT_FEATURE_FLAG_CALLED_CACHE_SIZE,
    /**
     * Enable local evaluation of feature flags
     * When enabled, the SDK periodically fetches flag definitions and evaluates flags locally
     * without making API calls for each flag check. Falls back to API if evaluation is inconclusive.
     * Requires personalApiKey to be set.
     * Defaults to false
     */
    public var localEvaluation: Boolean = false,
    /**
     * Personal API key for local evaluation
     * Required when localEvaluation is true.
     * Defaults to null
     */
    personalApiKey: String? = null,
    /**
     * Interval in seconds for polling feature flag definitions for local evaluation
     * Defaults to 30 seconds
     */
    public var pollIntervalSeconds: Int = DEFAULT_POLL_INTERVAL_SECONDS,
    /**
     * Evaluation contexts for feature flags
     * List of contexts (e.g., "web", "mobile", "checkout") used to filter
     * feature flag evaluations on the server side.
     * Defaults to null
     */
    public var evaluationContexts: List<String>? = null,
) {
    /**
     * The PostHog project API key, trimmed of leading and trailing whitespace.
     */
    public val apiKey: String = apiKey.trim()

    /**
     * The PostHog Host
     * Defaults to https://us.i.posthog.com
     * EU Host: https://eu.i.posthog.com
     */
    public val host: String = host.trim().ifBlank { DEFAULT_HOST }

    /**
     * Personal API key for local evaluation
     * Required when localEvaluation is true.
     * Defaults to null
     */
    public var personalApiKey: String? = personalApiKey?.trim()?.ifBlank { null }

    /**
     * Shared cache provider for local-evaluation feature flag definitions.
     *
     * This can reduce duplicate definition fetches when multiple SDK instances run in
     * the same service. Defaults to null.
     */
    public var flagDefinitionCacheProvider: PostHogFlagDefinitionCacheProvider? = null

    private val beforeSendCallbacks = mutableListOf<PostHogBeforeSend>()
    private val integrations = mutableListOf<PostHogIntegration>()

    /**
     * Adds a before-send hook that can mutate or drop events before they are queued.
     *
     * @param beforeSend Hook to add.
     */
    public fun addBeforeSend(beforeSend: PostHogBeforeSend) {
        beforeSendCallbacks.add(beforeSend)
    }

    /**
     * Removes a previously added before-send hook.
     *
     * @param beforeSend Hook to remove.
     */
    public fun removeBeforeSend(beforeSend: PostHogBeforeSend) {
        beforeSendCallbacks.remove(beforeSend)
    }

    /**
     * Adds an integration to install when the SDK is set up.
     *
     * @param integration Integration to add.
     */
    public fun addIntegration(integration: PostHogIntegration) {
        integrations.add(integration)
    }

    @Suppress("DEPRECATION")
    @JvmSynthetic
    internal fun asCoreConfig(): com.posthog.PostHogConfig {
        val coreConfig =
            com.posthog.PostHogConfig(
                apiKey = apiKey,
                host = host,
                debug = debug,
                sendFeatureFlagEvent = sendFeatureFlagEvent,
                preloadFeatureFlags = preloadFeatureFlags,
                remoteConfig = remoteConfig,
                flushAt = flushAt,
                maxQueueSize = maxQueueSize,
                maxBatchSize = maxBatchSize,
                flushIntervalSeconds = flushIntervalSeconds,
                encryption = encryption,
                onFeatureFlags = onFeatureFlags,
                proxy = proxy,
                remoteConfigProvider = { config, api, _, _, _, _ ->
                    PostHogFeatureFlags(
                        config,
                        api,
                        cacheMaxAgeMs = featureFlagCacheMaxAgeMs,
                        cacheMaxSize = featureFlagCacheSize,
                        localEvaluation = localEvaluation,
                        personalApiKey = personalApiKey,
                        pollIntervalSeconds = pollIntervalSeconds,
                        onFeatureFlags = onFeatureFlags,
                        flagDefinitionCacheProvider = flagDefinitionCacheProvider,
                    )
                },
                queueProvider = { config, api, endpoint, _, executor ->
                    PostHogMemoryQueue(config, api, endpoint, executor)
                },
            )

        // Apply stored callbacks and integrations
        beforeSendCallbacks.forEach { coreConfig.addBeforeSend(it) }
        integrations.forEach { coreConfig.addIntegration(it) }

        // Set SDK identification
        coreConfig.sdkName = BuildConfig.SDK_NAME
        coreConfig.sdkVersion = BuildConfig.VERSION_NAME
        coreConfig.context = PostHogServerContext(coreConfig)

        // Propagate evaluationContexts if set
        coreConfig.evaluationContexts = evaluationContexts

        return coreConfig
    }

    public companion object {
        public const val DEFAULT_US_HOST: String = "https://us.i.posthog.com"
        public const val DEFAULT_US_ASSETS_HOST: String = "https://us-assets.i.posthog.com"

        // flutter uses it
        public const val DEFAULT_HOST: String = DEFAULT_US_HOST

        public const val DEFAULT_EU_HOST: String = "https://eu.i.posthog.com"
        public const val DEFAULT_EU_ASSETS_HOST: String = "https://eu-assets.i.posthog.com"

        public const val DEFAULT_FLUSH_AT: Int = 20
        public const val DEFAULT_MAX_QUEUE_SIZE: Int = 1000
        public const val DEFAULT_MAX_BATCH_SIZE: Int = 50
        public const val DEFAULT_FLUSH_INTERVAL_SECONDS: Int = 30
        public const val DEFAULT_FEATURE_FLAG_CACHE_SIZE: Int = 1000
        public const val DEFAULT_FEATURE_FLAG_CACHE_MAX_AGE_MS: Int = 5 * 60 * 1000 // 5 minutes
        public const val DEFAULT_FEATURE_FLAG_CALLED_CACHE_SIZE: Int = 1000
        public const val DEFAULT_POLL_INTERVAL_SECONDS: Int = 30

        /**
         * Creates a Java-friendly builder.
         *
         * @param apiKey PostHog project API key.
         * @return A new [Builder].
         */
        @JvmStatic
        public fun builder(apiKey: String): Builder = Builder(apiKey)
    }

    /**
     * Java-friendly builder for [PostHogConfig].
     *
     * @param apiKey PostHog project API key.
     */
    public class Builder(private val apiKey: String) {
        private var host: String = DEFAULT_HOST
        private var debug: Boolean = false
        private var sendFeatureFlagEvent: Boolean = true
        private var preloadFeatureFlags: Boolean = true

        @Suppress("DEPRECATION")
        private var remoteConfig: Boolean = true
        private var flushAt: Int = DEFAULT_FLUSH_AT
        private var maxQueueSize: Int = DEFAULT_MAX_QUEUE_SIZE
        private var maxBatchSize: Int = DEFAULT_MAX_BATCH_SIZE
        private var flushIntervalSeconds: Int = DEFAULT_FLUSH_INTERVAL_SECONDS
        private var encryption: PostHogEncryption? = null
        private var onFeatureFlags: PostHogOnFeatureFlags? = null
        private var proxy: Proxy? = null
        private var featureFlagCacheSize: Int = DEFAULT_FEATURE_FLAG_CACHE_SIZE
        private var featureFlagCacheMaxAgeMs: Int = DEFAULT_FEATURE_FLAG_CACHE_MAX_AGE_MS
        private var featureFlagCalledCacheSize: Int = DEFAULT_FEATURE_FLAG_CALLED_CACHE_SIZE
        private var localEvaluation: Boolean? = null
        private var personalApiKey: String? = null
        private var pollIntervalSeconds: Int = DEFAULT_POLL_INTERVAL_SECONDS
        private var evaluationContexts: List<String>? = null
        private var flagDefinitionCacheProvider: PostHogFlagDefinitionCacheProvider? = null

        /**
         * Sets the PostHog ingestion host.
         *
         * @param host Host URL, usually [DEFAULT_US_HOST] or [DEFAULT_EU_HOST].
         * @return This builder.
         */
        public fun host(host: String): Builder = apply { this.host = host }

        /**
         * Enables or disables SDK debug logging.
         *
         * @param debug true to enable detailed SDK logs.
         * @return This builder.
         */
        public fun debug(debug: Boolean): Builder = apply { this.debug = debug }

        /**
         * Sets whether feature flag lookups send `$feature_flag_called` events by default.
         *
         * @param sendFeatureFlagEvent true to send usage events automatically.
         * @return This builder.
         */
        public fun sendFeatureFlagEvent(sendFeatureFlagEvent: Boolean): Builder = apply { this.sendFeatureFlagEvent = sendFeatureFlagEvent }

        /**
         * Sets whether feature flags are preloaded automatically during setup.
         *
         * @param preloadFeatureFlags true to preload feature flags.
         * @return This builder.
         */
        public fun preloadFeatureFlags(preloadFeatureFlags: Boolean): Builder = apply { this.preloadFeatureFlags = preloadFeatureFlags }

        /**
         * Sets the remote config preload flag.
         *
         * This option is deprecated because remote config is always enabled.
         *
         * @param remoteConfig Deprecated no-op value.
         * @return This builder.
         */
        @Deprecated(
            message = "Remote config is now always enabled. This option is a no-op and will be removed in a future version.",
            level = DeprecationLevel.WARNING,
        )
        @Suppress("DEPRECATION")
        public fun remoteConfig(remoteConfig: Boolean): Builder = apply { this.remoteConfig = remoteConfig }

        /**
         * Sets how many events are queued before an automatic flush.
         *
         * @param flushAt Event count threshold.
         * @return This builder.
         */
        public fun flushAt(flushAt: Int): Builder = apply { this.flushAt = flushAt }

        /**
         * Sets the maximum number of queued events kept in memory.
         *
         * @param maxQueueSize Maximum queued events.
         * @return This builder.
         */
        public fun maxQueueSize(maxQueueSize: Int): Builder = apply { this.maxQueueSize = maxQueueSize }

        /**
         * Sets the maximum number of events sent in one batch request.
         *
         * @param maxBatchSize Maximum batch size.
         * @return This builder.
         */
        public fun maxBatchSize(maxBatchSize: Int): Builder = apply { this.maxBatchSize = maxBatchSize }

        /**
         * Sets the periodic flush interval in seconds.
         *
         * @param flushIntervalSeconds Flush interval in seconds.
         * @return This builder.
         */
        public fun flushIntervalSeconds(flushIntervalSeconds: Int): Builder = apply { this.flushIntervalSeconds = flushIntervalSeconds }

        /**
         * Sets custom event encryption hooks.
         *
         * @param encryption Encryption implementation, or null for no encryption.
         * @return This builder.
         */
        public fun encryption(encryption: PostHogEncryption?): Builder = apply { this.encryption = encryption }

        /**
         * Sets the callback invoked when feature flags are loaded.
         *
         * @param onFeatureFlags Callback, or null for no callback.
         * @return This builder.
         */
        public fun onFeatureFlags(onFeatureFlags: PostHogOnFeatureFlags?): Builder = apply { this.onFeatureFlags = onFeatureFlags }

        /**
         * Sets an HTTP proxy for PostHog API requests.
         *
         * @param proxy Proxy to use, or null for no proxy.
         * @return This builder.
         */
        public fun proxy(proxy: Proxy?): Builder = apply { this.proxy = proxy }

        /**
         * Sets the maximum number of feature flag results cached in memory.
         *
         * @param featureFlagCacheSize Maximum cache entries.
         * @return This builder.
         */
        public fun featureFlagCacheSize(featureFlagCacheSize: Int): Builder = apply { this.featureFlagCacheSize = featureFlagCacheSize }

        /**
         * Sets the maximum age of a cached feature flag result in milliseconds.
         *
         * @param featureFlagCacheMaxAgeMs Maximum cache age in milliseconds.
         * @return This builder.
         */
        public fun featureFlagCacheMaxAgeMs(featureFlagCacheMaxAgeMs: Int): Builder =
            apply { this.featureFlagCacheMaxAgeMs = featureFlagCacheMaxAgeMs }

        /**
         * Sets the maximum number of distinct `$feature_flag_called` events tracked for deduplication.
         *
         * @param featureFlagCalledCacheSize Maximum deduplication cache entries.
         * @return This builder.
         */
        public fun featureFlagCalledCacheSize(featureFlagCalledCacheSize: Int): Builder =
            apply { this.featureFlagCalledCacheSize = featureFlagCalledCacheSize }

        /**
         * Enables or disables local feature flag evaluation.
         *
         * @param localEvaluation true to periodically fetch definitions and evaluate flags locally.
         * @return This builder.
         */
        public fun localEvaluation(localEvaluation: Boolean): Builder = apply { this.localEvaluation = localEvaluation }

        /**
         * Sets the personal API key used for local feature flag evaluation.
         *
         * Setting a non-blank personal API key automatically enables [localEvaluation] unless it has
         * already been set explicitly.
         *
         * @param personalApiKey Personal API key, or null/blank to clear it.
         * @return This builder.
         */
        public fun personalApiKey(personalApiKey: String?): Builder =
            apply {
                this.personalApiKey = personalApiKey?.trim()?.ifBlank { null }
                if (localEvaluation == null) {
                    this.localEvaluation = this.personalApiKey != null
                }
            }

        /**
         * Sets how often local evaluation flag definitions are polled.
         *
         * @param pollIntervalSeconds Poll interval in seconds.
         * @return This builder.
         */
        public fun pollIntervalSeconds(pollIntervalSeconds: Int): Builder = apply { this.pollIntervalSeconds = pollIntervalSeconds }

        /**
         * Sets evaluation context tags that constrain which feature flags are evaluated.
         *
         * @param evaluationContexts Context tags, or null to evaluate all flags.
         * @return This builder.
         */
        public fun evaluationContexts(evaluationContexts: List<String>?): Builder = apply { this.evaluationContexts = evaluationContexts }

        /**
         * Sets the provider for caching feature flag definitions.
         *
         * @param flagDefinitionCacheProvider The cache provider, or null to use the default.
         * @return This builder.
         */
        public fun flagDefinitionCacheProvider(flagDefinitionCacheProvider: PostHogFlagDefinitionCacheProvider?): Builder =
            apply { this.flagDefinitionCacheProvider = flagDefinitionCacheProvider }

        /**
         * Builds a [PostHogConfig] from the accumulated values.
         *
         * @return The configured server SDK config.
         */
        @Suppress("DEPRECATION")
        public fun build(): PostHogConfig {
            val config =
                PostHogConfig(
                    apiKey = apiKey,
                    host = host,
                    debug = debug,
                    sendFeatureFlagEvent = sendFeatureFlagEvent,
                    preloadFeatureFlags = preloadFeatureFlags,
                    remoteConfig = remoteConfig,
                    flushAt = flushAt,
                    maxQueueSize = maxQueueSize,
                    maxBatchSize = maxBatchSize,
                    flushIntervalSeconds = flushIntervalSeconds,
                    encryption = encryption,
                    onFeatureFlags = onFeatureFlags,
                    proxy = proxy,
                    featureFlagCacheSize = featureFlagCacheSize,
                    featureFlagCacheMaxAgeMs = featureFlagCacheMaxAgeMs,
                    featureFlagCalledCacheSize = featureFlagCalledCacheSize,
                    localEvaluation = localEvaluation ?: false,
                    personalApiKey = personalApiKey,
                    pollIntervalSeconds = pollIntervalSeconds,
                    evaluationContexts = evaluationContexts,
                )
            config.flagDefinitionCacheProvider = flagDefinitionCacheProvider
            return config
        }
    }
}

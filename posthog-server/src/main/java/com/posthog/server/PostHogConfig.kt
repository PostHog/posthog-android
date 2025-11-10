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
 * The SDK Config
 */
public open class PostHogConfig constructor(
    /**
     * The PostHog API Key
     */
    public val apiKey: String,
    /**
     * The PostHog Host
     * Defaults to https://us.i.posthog.com
     * EU Host: https://eu.i.posthog.com
     *
     */
    public val host: String = DEFAULT_HOST,
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
     * Hook that allows to sanitize the event properties
     * The hook is called before the event is cached or sent over the wire
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
    public var personalApiKey: String? = null,
    /**
     * Interval in seconds for polling feature flag definitions for local evaluation
     * Defaults to 30 seconds
     */
    public var pollIntervalSeconds: Int = DEFAULT_POLL_INTERVAL_SECONDS,
) {
    private val beforeSendCallbacks = mutableListOf<PostHogBeforeSend>()
    private val integrations = mutableListOf<PostHogIntegration>()

    public fun addBeforeSend(beforeSend: PostHogBeforeSend) {
        beforeSendCallbacks.add(beforeSend)
    }

    public fun removeBeforeSend(beforeSend: PostHogBeforeSend) {
        beforeSendCallbacks.remove(beforeSend)
    }

    public fun addIntegration(integration: PostHogIntegration) {
        integrations.add(integration)
    }

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
                remoteConfigProvider = { config, api, _, _ ->
                    PostHogFeatureFlags(
                        config,
                        api,
                        cacheMaxAgeMs = featureFlagCacheMaxAgeMs,
                        cacheMaxSize = featureFlagCacheSize,
                        localEvaluation = localEvaluation,
                        personalApiKey = personalApiKey,
                        pollIntervalSeconds = pollIntervalSeconds,
                        onFeatureFlags = onFeatureFlags,
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

        @JvmStatic
        public fun builder(apiKey: String): Builder = Builder(apiKey)
    }

    public class Builder(private val apiKey: String) {
        private var host: String = DEFAULT_HOST
        private var debug: Boolean = false
        private var sendFeatureFlagEvent: Boolean = true
        private var preloadFeatureFlags: Boolean = true
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

        public fun host(host: String): Builder = apply { this.host = host }

        public fun debug(debug: Boolean): Builder = apply { this.debug = debug }

        public fun sendFeatureFlagEvent(sendFeatureFlagEvent: Boolean): Builder = apply { this.sendFeatureFlagEvent = sendFeatureFlagEvent }

        public fun preloadFeatureFlags(preloadFeatureFlags: Boolean): Builder = apply { this.preloadFeatureFlags = preloadFeatureFlags }

        public fun remoteConfig(remoteConfig: Boolean): Builder = apply { this.remoteConfig = remoteConfig }

        public fun flushAt(flushAt: Int): Builder = apply { this.flushAt = flushAt }

        public fun maxQueueSize(maxQueueSize: Int): Builder = apply { this.maxQueueSize = maxQueueSize }

        public fun maxBatchSize(maxBatchSize: Int): Builder = apply { this.maxBatchSize = maxBatchSize }

        public fun flushIntervalSeconds(flushIntervalSeconds: Int): Builder = apply { this.flushIntervalSeconds = flushIntervalSeconds }

        public fun encryption(encryption: PostHogEncryption?): Builder = apply { this.encryption = encryption }

        public fun onFeatureFlags(onFeatureFlags: PostHogOnFeatureFlags?): Builder = apply { this.onFeatureFlags = onFeatureFlags }

        public fun proxy(proxy: Proxy?): Builder = apply { this.proxy = proxy }

        public fun featureFlagCacheSize(featureFlagCacheSize: Int): Builder = apply { this.featureFlagCacheSize = featureFlagCacheSize }

        public fun featureFlagCacheMaxAgeMs(featureFlagCacheMaxAgeMs: Int): Builder =
            apply { this.featureFlagCacheMaxAgeMs = featureFlagCacheMaxAgeMs }

        public fun featureFlagCalledCacheSize(featureFlagCalledCacheSize: Int): Builder =
            apply { this.featureFlagCalledCacheSize = featureFlagCalledCacheSize }

        public fun localEvaluation(localEvaluation: Boolean): Builder = apply { this.localEvaluation = localEvaluation }

        public fun personalApiKey(personalApiKey: String?): Builder =
            apply {
                this.personalApiKey = personalApiKey
                if (localEvaluation == null) {
                    this.localEvaluation = personalApiKey != null
                }
            }

        public fun pollIntervalSeconds(pollIntervalSeconds: Int): Builder = apply { this.pollIntervalSeconds = pollIntervalSeconds }

        public fun build(): PostHogConfig =
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
            )
    }
}

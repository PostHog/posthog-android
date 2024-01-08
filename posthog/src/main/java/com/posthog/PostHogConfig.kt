package com.posthog

import com.posthog.internal.PostHogContext
import com.posthog.internal.PostHogDateProvider
import com.posthog.internal.PostHogDeviceDateProvider
import com.posthog.internal.PostHogLogger
import com.posthog.internal.PostHogNetworkStatus
import com.posthog.internal.PostHogPreferences
import com.posthog.internal.PostHogPrintLogger
import com.posthog.internal.PostHogSerializer

/**
 * The SDK Config
 */
public open class PostHogConfig(

    /**
     * The PostHog API Key
     */
    public val apiKey: String,

    /**
     * The PostHog Host
     * Defaults to https://app.posthog.com
     */
    public val host: String = defaultHost,

    /**
     * Logs the debug logs to the [logger] if enabled
     * Defaults to false
     */
    public var debug: Boolean = false,

    /**
     * This flag prevents capturing any data if enabled
     * You can overwrite this value at runtime by calling [PostHog.optIn()]] or PostHog.optOut()
     * Defaults to false
     */
    @Volatile
    public var optOut: Boolean = false,

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
     * Number of minimum events before they are sent over the wire
     * Defaults to 20
     */
    public var flushAt: Int = 20,

    /**
     * Number of maximum events in memory and disk, when the maximum is exceed, the oldest
     * event is deleted and the new one takes place
     * Defaults to 1000
     */
    public var maxQueueSize: Int = 1000,
    /**
     * Number of maximum events in a batch call
     * Defaults to 50
     */
    public var maxBatchSize: Int = 50,
//    (30).toDuration(DurationUnit.SECONDS) requires Kotlin 1.6
    /**
     * Interval in seconds for sending events over the wire
     * The lower the number, most likely more battery is used
     * Defaults to 30s
     */
    public var flushIntervalSeconds: Int = 30,

    /**
     * Hook for encrypt and decrypt events
     * Devices are sandbox so likely not needed
     * Defaults to no encryption
     */
    public var encryption: PostHogEncryption? = null,

    /**
     * Hook that is called when feature flags are loaded
     * Defaults to no callback
     */
    public var onFeatureFlags: PostHogOnFeatureFlags? = null,

    /**
     * Enable Recording of Session Replays for Android
     * Requires Authorized Domains to be disabled in the PostHog Project Settings
     * Requires Record user sessions to be enabled in the PostHog Project Settings
     * Defaults to false
     */
    @PostHogExperimental
    public var sessionReplay: Boolean = false,

    /**
     * Hook that allows to sanitize the event properties
     * The hook is called before the event is cached or sent over the wire
     */
    public var propertiesSanitizer: PostHogPropertiesSanitizer? = null,

) {
    // fix me: https://stackoverflow.com/questions/53866865/leaking-this-in-constructor-warning-should-apply-to-final-classes-as-well-as
    @PostHogInternal
    public var logger: PostHogLogger = PostHogPrintLogger(this)

    @PostHogInternal
    public val serializer: PostHogSerializer by lazy {
        PostHogSerializer(this)
    }

    @PostHogInternal
    public var context: PostHogContext? = null

    @PostHogInternal
    public var sdkName: String = "posthog-java"

    @PostHogInternal
    public var sdkVersion: String = BuildConfig.VERSION_NAME

    internal val userAgent: String = "$sdkName/$sdkVersion"

    @PostHogInternal
    public var legacyStoragePrefix: String? = null

    @PostHogInternal
    public var storagePrefix: String? = null

    @PostHogInternal
    public var replayStoragePrefix: String? = null

    @PostHogInternal
    public var cachePreferences: PostHogPreferences? = null

    @PostHogInternal
    public var networkStatus: PostHogNetworkStatus? = null

    @PostHogInternal
    public var snapshotEndpoint: String = "/s/"

    @PostHogInternal
    public var dateProvider: PostHogDateProvider = PostHogDeviceDateProvider()

    private val integrationsList: MutableList<PostHogIntegration> = mutableListOf()
    private val integrationLock = Any()

    /**
     * The integrations list
     */
    public val integrations: List<PostHogIntegration>
        get() {
            val list: List<PostHogIntegration>
            synchronized(integrationLock) {
                list = integrationsList.toList()
            }
            return list
        }

    /**
     * Adds a new integration
     * @param integration the Integration
     */
    public fun addIntegration(integration: PostHogIntegration) {
        synchronized(integrationLock) {
            integrationsList.add(integration)
        }
    }

    /**
     * Removes the integration
     * @param integration the Integration
     */
    public fun removeIntegration(integration: PostHogIntegration) {
        synchronized(integrationLock) {
            integrationsList.remove(integration)
        }
    }

    public companion object {
        public const val defaultHost: String = "https://app.posthog.com"
    }
}

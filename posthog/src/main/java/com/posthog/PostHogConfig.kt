package com.posthog

import com.posthog.errortracking.PostHogErrorTrackingConfig
import com.posthog.internal.PostHogApi
import com.posthog.internal.PostHogApiEndpoint
import com.posthog.internal.PostHogContext
import com.posthog.internal.PostHogDateProvider
import com.posthog.internal.PostHogDeviceDateProvider
import com.posthog.internal.PostHogFeatureFlagsInterface
import com.posthog.internal.PostHogLogger
import com.posthog.internal.PostHogNetworkStatus
import com.posthog.internal.PostHogNoOpLogger
import com.posthog.internal.PostHogPreferences
import com.posthog.internal.PostHogQueue
import com.posthog.internal.PostHogQueueInterface
import com.posthog.internal.PostHogRemoteConfig
import com.posthog.internal.PostHogSerializer
import com.posthog.surveys.PostHogSurveysConfig
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.ExecutorService

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
     * Maximum number of distinct feature flag calls to cache for deduplication
     * When the cache is full, the least recently used entries are evicted
     * Defaults to 1000
     */
    public var featureFlagCalledCacheSize: Int = DEFAULT_FEATURE_FLAG_CALLED_CACHE_SIZE,
    /**
     * Preload feature flags automatically
     * Docs https://posthog.com/docs/feature-flags and https://posthog.com/docs/experiments
     * Defaults to true
     */
    public var preloadFeatureFlags: Boolean = true,
    /**
     * Optional list of evaluation environment tags for feature flag evaluation
     * When specified, only feature flags that have at least one matching evaluation tag will be evaluated
     * Feature flags with no evaluation tags will always be evaluated for backward compatibility
     * Example: listOf("production", "web", "checkout")
     * Defaults to null (evaluate all flags)
     */
    public var evaluationEnvironments: List<String>? = null,
    /**
     * Automatically set common device and app properties as person properties for feature flag evaluation.
     *
     * When enabled, the SDK will automatically set the following person properties:
     * - $app_version: App version from package info
     * - $app_build: App build number from package info
     * - $app_namespace: App namespace from package info
     * - $os_name: Operating system name (Android)
     * - $os_version: Operating system version
     * - $device_type: Device type (Mobile, Tablet, TV, etc.)
     * - $lib: The identifier of the SDK
     * - $lib_version: The version of the SDK
     *
     * This helps ensure feature flags that rely on these properties work correctly
     * without waiting for server-side processing of identify() calls.
     *
     * Default: true
     */
    public var setDefaultPersonProperties: Boolean = true,
    /**
     * Preload PostHog remote config automatically
     * Defaults to true
     */
    @PostHogExperimental
    public var remoteConfig: Boolean = true,
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
    // (30).toDuration(DurationUnit.SECONDS) requires Kotlin 1.6
    /**
     * Interval in seconds for sending events over the wire
     * The lower the number, most likely more battery is used
     * Defaults to 30s
     */
    @Suppress("ktlint:standard:no-consecutive-comments")
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
     * Requires Record user sessions to be enabled in the PostHog Project Settings
     * Defaults to false
     */
    public var sessionReplay: Boolean = false,
    /**
     * Hook that allows to sanitize the event properties
     * The hook is called before the event is cached or sent over the wire
     */
    @Deprecated("Use beforeSendList instead")
    public var propertiesSanitizer: PostHogPropertiesSanitizer? = null,
    /**
     * Hook that allows for modification of the default mechanism for
     * generating anonymous id (which as of now is just random UUID v7)
     */
    public var getAnonymousId: ((UUID) -> UUID) = { it },
    /**
     * Flag to reuse the anonymous id.
     * If enabled, the anonymous id will be reused across `identify()` and `reset()` calls.
     *
     * Events captured before the user has identified won't be linked to the identified user, e.g.:
     *
     * Guest user (anonymous id) captures an event (click on X).
     *
     * User logs in (User A) calls identify
     * User A captures an event (clicks on Y)
     * User A logs out (calls reset)
     *
     * Guest user (reused anonymous id) captures an event (click on Z)
     *
     * click on X and click on Z events will be associated to the same user (anonymous id)
     *
     * clicks on Y event will be associated only with User A
     *
     * This will allow you to reuse the anonymous id as a Guest user, but all the events happening before
     * or after the user logs in and logs out won't be associated.
     *
     * Defaults to false.
     */
    public var reuseAnonymousId: Boolean = false,
    /**
     * Determines the behavior for processing user profiles.
     * - `ALWAYS`: We will process persons data for all events.
     * - `NEVER`: Never processes user profile data. This means that anonymous users will not be merged when they sign up or log in.
     * - `IDENTIFIED_ONLY` (default): we will only process persons when you call `identify`, `alias`, and `group`, Anonymous users won't get person profiles.
     */
    public var personProfiles: PersonProfiles = PersonProfiles.IDENTIFIED_ONLY,
    /**
     * Enable Surveys for Android (Intended for internal use only)
     *
     * Note: Not fully supported or documented yet. Primarily used by hybrid SDKs to attach a custom display logic.
     *
     * Requires Surveys to be enabled in the PostHog Project Settings
     * Defaults to false
     */
    public var surveys: Boolean = false,
    /**
     * Configures an optional HTTP proxy for the PostHog API client.
     *
     * When set, all requests made will be routed through the specified proxy server.
     *
     * Example:
     * ```
     * val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("proxy.example.com",8080))
     * ```
     * - `type`: Type representing the proxy type
     * - `hostname`: The hostname or IP address of the proxy server.
     * - `port`: The port number on which the proxy server is listening.
     *
     * Default: `null` (no proxy).
     */
    public var proxy: Proxy? = null,
    /**
     * Configuration for PostHog Surveys feature. (Intended for internal use only)
     *
     * Note: Not fully supported or documented yet. Primarily used by hybrid SDKs to attach a custom display logic.
     */
    public var surveysConfig: PostHogSurveysConfig = PostHogSurveysConfig(),
    /**
     * Factory to instantiate a custom [com.posthog.internal.PostHogRemoteConfigInterface] implementation.
     */
    public val remoteConfigProvider: (
        PostHogConfig,
        PostHogApi,
        ExecutorService,
        (() -> Map<String, Any>)?,
    ) -> PostHogFeatureFlagsInterface =
        {
                config,
                api,
                executor,
                getDefaultPersonProperties,
            ->
            PostHogRemoteConfig(config, api, executor, getDefaultPersonProperties ?: { emptyMap() })
        },
    /**
     * Factory to instantiate a custom queue implementation.
     */
    public val queueProvider: (PostHogConfig, PostHogApi, PostHogApiEndpoint, String?, ExecutorService) -> PostHogQueueInterface =
        { config, api, endpoint, storagePrefix, executor -> PostHogQueue(config, api, endpoint, storagePrefix, executor) },
    /**
     * Configuration for PostHog Error Tracking feature.
     */
    public val errorTrackingConfig: PostHogErrorTrackingConfig = PostHogErrorTrackingConfig(),
) {
    @PostHogInternal
    public var logger: PostHogLogger = PostHogNoOpLogger()

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

    internal val userAgent: String
        get() {
            return "$sdkName/$sdkVersion"
        }

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
     * Hook that allows to sanitize the event
     * The hook is called before the event is cached or sent over the wire
     */
    private val beforeSend: MutableList<PostHogBeforeSend> = mutableListOf()
    private val beforeSendLock = Any()

    /**
     * The beforeSend list
     */
    public val beforeSendList: List<PostHogBeforeSend>
        get() {
            val list: List<PostHogBeforeSend>
            synchronized(beforeSendLock) {
                list = beforeSend.toList()
            }
            return list
        }

    /**
     * Adds a new PostHogBeforeSend
     * @param beforeSend the beforeSend
     */
    public fun addBeforeSend(beforeSend: PostHogBeforeSend) {
        synchronized(beforeSendLock) {
            this.beforeSend.add(beforeSend)
        }
    }

    /**
     * Removes the PostHogBeforeSend
     * @param beforeSend the beforeSend
     */
    public fun removeBeforeSend(beforeSend: PostHogBeforeSend) {
        synchronized(beforeSendLock) {
            this.beforeSend.remove(beforeSend)
        }
    }

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
        public const val DEFAULT_US_HOST: String = "https://us.i.posthog.com"
        public const val DEFAULT_US_ASSETS_HOST: String = "https://us-assets.i.posthog.com"

        // flutter uses it
        public const val DEFAULT_HOST: String = DEFAULT_US_HOST

        public const val DEFAULT_EU_HOST: String = "https://eu.i.posthog.com"
        public const val DEFAULT_EU_ASSETS_HOST: String = "https://eu-assets.i.posthog.com"

        public const val DEFAULT_FEATURE_FLAG_CALLED_CACHE_SIZE: Int = 1000
    }
}

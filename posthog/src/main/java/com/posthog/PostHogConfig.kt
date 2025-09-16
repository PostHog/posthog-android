package com.posthog

import com.posthog.internal.PostHogContext
import com.posthog.internal.PostHogDateProvider
import com.posthog.internal.PostHogDeviceDateProvider
import com.posthog.internal.PostHogLogger
import com.posthog.internal.PostHogNetworkStatus
import com.posthog.internal.PostHogNoOpLogger
import com.posthog.internal.PostHogPreferences
import com.posthog.internal.PostHogSerializer
import com.posthog.surveys.PostHogSurveysConfig
import java.net.Proxy
import java.util.UUID

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
     * Preload PostHog remote config automatically
     * Defaults to true
     */
    @PostHogExperimental
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
    // (30).toDuration(DurationUnit.SECONDS) requires Kotlin 1.6
    /**
     * Interval in seconds for sending events over the wire
     * The lower the number, most likely more battery is used
     * Defaults to 30s
     */
    @Suppress("ktlint:standard:no-consecutive-comments")
    public var flushIntervalSeconds: Int = DEFAULT_FLUSH_INTERVAL_SECONDS,
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
     * Enable Surveys for Android
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
     * Configuration for PostHog Surveys feature.
     */
    public var surveysConfig: PostHogSurveysConfig = PostHogSurveysConfig(),
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

        public const val DEFAULT_FLUSH_AT: Int = 20
        public const val DEFAULT_MAX_QUEUE_SIZE: Int = 1000
        public const val DEFAULT_MAX_BATCH_SIZE: Int = 50
        public const val DEFAULT_FLUSH_INTERVAL_SECONDS: Int = 30

        @JvmStatic
        public fun builder(apiKey: String): Builder = Builder(apiKey)
    }

    public class Builder(private val apiKey: String) {
        private var host: String = DEFAULT_HOST
        private var debug: Boolean = false
        private var optOut: Boolean = false
        private var sendFeatureFlagEvent: Boolean = true
        private var preloadFeatureFlags: Boolean = true
        private var remoteConfig: Boolean = true
        private var flushAt: Int = DEFAULT_FLUSH_AT
        private var maxQueueSize: Int = DEFAULT_MAX_QUEUE_SIZE
        private var maxBatchSize: Int = DEFAULT_MAX_BATCH_SIZE
        private var flushIntervalSeconds: Int = DEFAULT_FLUSH_INTERVAL_SECONDS
        private var encryption: PostHogEncryption? = null
        private var onFeatureFlags: PostHogOnFeatureFlags? = null
        private var sessionReplay: Boolean = false
        private var propertiesSanitizer: PostHogPropertiesSanitizer? = null
        private var getAnonymousId: ((UUID) -> UUID) = { it }
        private var reuseAnonymousId: Boolean = false
        private var personProfiles: PersonProfiles = PersonProfiles.IDENTIFIED_ONLY
        private var surveys: Boolean = false
        private var proxy: Proxy? = null
        private var surveysConfig: PostHogSurveysConfig = PostHogSurveysConfig()

        public fun host(host: String): Builder = apply { this.host = host }

        public fun debug(debug: Boolean): Builder = apply { this.debug = debug }

        public fun optOut(optOut: Boolean): Builder = apply { this.optOut = optOut }

        public fun sendFeatureFlagEvent(sendFeatureFlagEvent: Boolean): Builder = apply { this.sendFeatureFlagEvent = sendFeatureFlagEvent }

        public fun preloadFeatureFlags(preloadFeatureFlags: Boolean): Builder = apply { this.preloadFeatureFlags = preloadFeatureFlags }

        public fun remoteConfig(remoteConfig: Boolean): Builder = apply { this.remoteConfig = remoteConfig }

        public fun flushAt(flushAt: Int): Builder = apply { this.flushAt = flushAt }

        public fun maxQueueSize(maxQueueSize: Int): Builder = apply { this.maxQueueSize = maxQueueSize }

        public fun maxBatchSize(maxBatchSize: Int): Builder = apply { this.maxBatchSize = maxBatchSize }

        public fun flushIntervalSeconds(flushIntervalSeconds: Int): Builder = apply { this.flushIntervalSeconds = flushIntervalSeconds }

        public fun encryption(encryption: PostHogEncryption?): Builder = apply { this.encryption = encryption }

        public fun onFeatureFlags(onFeatureFlags: PostHogOnFeatureFlags?): Builder = apply { this.onFeatureFlags = onFeatureFlags }

        public fun sessionReplay(sessionReplay: Boolean): Builder = apply { this.sessionReplay = sessionReplay }

        public fun propertiesSanitizer(propertiesSanitizer: PostHogPropertiesSanitizer?): Builder =
            apply {
                this.propertiesSanitizer = propertiesSanitizer
            }

        public fun getAnonymousId(getAnonymousId: (UUID) -> UUID): Builder = apply { this.getAnonymousId = getAnonymousId }

        public fun reuseAnonymousId(reuseAnonymousId: Boolean): Builder = apply { this.reuseAnonymousId = reuseAnonymousId }

        public fun personProfiles(personProfiles: PersonProfiles): Builder = apply { this.personProfiles = personProfiles }

        public fun surveys(surveys: Boolean): Builder = apply { this.surveys = surveys }

        public fun proxy(proxy: Proxy?): Builder = apply { this.proxy = proxy }

        public fun surveysConfig(surveysConfig: PostHogSurveysConfig): Builder = apply { this.surveysConfig = surveysConfig }

        public fun build(): PostHogConfig =
            PostHogConfig(
                apiKey = apiKey,
                host = host,
                debug = debug,
                optOut = optOut,
                sendFeatureFlagEvent = sendFeatureFlagEvent,
                preloadFeatureFlags = preloadFeatureFlags,
                remoteConfig = remoteConfig,
                flushAt = flushAt,
                maxQueueSize = maxQueueSize,
                maxBatchSize = maxBatchSize,
                flushIntervalSeconds = flushIntervalSeconds,
                encryption = encryption,
                onFeatureFlags = onFeatureFlags,
                sessionReplay = sessionReplay,
                propertiesSanitizer = propertiesSanitizer,
                getAnonymousId = getAnonymousId,
                reuseAnonymousId = reuseAnonymousId,
                personProfiles = personProfiles,
                surveys = surveys,
                proxy = proxy,
                surveysConfig = surveysConfig,
            )
    }
}

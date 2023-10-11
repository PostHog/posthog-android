package com.posthog

import com.posthog.internal.PostHogApi
import com.posthog.internal.PostHogCalendarDateProvider
import com.posthog.internal.PostHogFeatureFlags
import com.posthog.internal.PostHogMemoryPreferences
import com.posthog.internal.PostHogQueue
import com.posthog.internal.PostHogSendCachedEventsIntegration
import com.posthog.internal.PostHogSerializer
import com.posthog.internal.PostHogThreadFactory
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

public class PostHog private constructor(
    private val queueExecutor: ExecutorService = Executors.newSingleThreadScheduledExecutor(
        PostHogThreadFactory("PostHogQueueThread"),
    ),
    private val featureFlagsExecutor: ExecutorService = Executors.newSingleThreadScheduledExecutor(
        PostHogThreadFactory("PostHogFeatureFlagsThread"),
    ),
    private val cachedEventsExecutor: ExecutorService = Executors.newSingleThreadScheduledExecutor(
        PostHogThreadFactory("PostHogSendCachedEventsThread"),
    ),
    private val reloadFeatureFlags: Boolean = true,
) : PostHogInterface {
    @Volatile
    private var enabled = false

    private val lockSetup = Any()
    private val lockOptOut = Any()
    private val anonymousLock = Any()
    private val featureFlagsSentLock = Any()

    private var config: PostHogConfig? = null

    private var featureFlags: PostHogFeatureFlags? = null
    private var queue: PostHogQueue? = null
    private var memoryPreferences = PostHogMemoryPreferences()
    private val featureFlagsSent = mutableSetOf<String>()

    public override fun <T : PostHogConfig> setup(config: T) {
        synchronized(lockSetup) {
            try {
                if (enabled) {
                    config.logger.log("Setup called despite already being setup!")
                    return
                }

                if (!apiKeys.add(config.apiKey)) {
                    config.logger.log("API Key: ${config.apiKey} already has a PostHog instance.")
                }

                val cachePreferences = config.cachePreferences ?: PostHogMemoryPreferences()
                config.cachePreferences = cachePreferences
                val serializer = PostHogSerializer(config)
                val dateProvider = PostHogCalendarDateProvider()
                val api = PostHogApi(config, serializer, dateProvider)
                val queue = PostHogQueue(config, api, serializer, dateProvider, queueExecutor)
                val featureFlags = PostHogFeatureFlags(config, serializer, api, featureFlagsExecutor)

                // no need to lock optOut here since the setup is locked already
                val optOut = config.cachePreferences?.getValue(
                    "opt-out",
                    defaultValue = config.optOut,
                ) as? Boolean
                optOut?.let {
                    config.optOut = optOut
                }

                val startDate = dateProvider.currentDate()
                val sendCachedEventsIntegration = PostHogSendCachedEventsIntegration(
                    config,
                    api,
                    serializer,
                    startDate,
                    cachedEventsExecutor,
                )

                this.config = config
                this.queue = queue
                this.featureFlags = featureFlags

                config.addIntegration(sendCachedEventsIntegration)

                legacyPreferences(config, serializer)

                enabled = true

                queue.start()

                config.integrations.forEach {
                    try {
                        it.install()
                    } catch (e: Throwable) {
                        config.logger.log("Integration ${it.javaClass.name} failed to install: $e.")
                    }
                }

                if (config.preloadFeatureFlags) {
                    loadFeatureFlagsRequest(config.onFeatureFlags)
                }
            } catch (e: Throwable) {
                config.logger.log("Setup failed: $e.")
            }
        }
    }

    private fun legacyPreferences(config: PostHogConfig, serializer: PostHogSerializer) {
        val cachedPrefs = config.cachePreferences?.getValue(config.apiKey) as? String
        cachedPrefs?.let {
            try {
                serializer.deserialize<Map<String, Any>?>(it.reader())?.let { props ->
                    val anonymousId = props["anonymousId"] as? String
                    val distinctId = props["distinctId"] as? String

                    anonymousId?.let { anon ->
                        this.anonymousId = anon
                    }
                    distinctId?.let { distId ->
                        this.distinctId = distId
                    }

                    config.cachePreferences?.remove(config.apiKey)
                }
            } catch (e: Throwable) {
                config.logger.log("Legacy cached prefs: $cachedPrefs failed to parse: $e.")
            }
        }
    }

    public override fun close() {
        synchronized(lockSetup) {
            try {
                enabled = false

                config?.let { config ->
                    apiKeys.remove(config.apiKey)

                    config.integrations.forEach {
                        try {
                            it.uninstall()
                        } catch (e: Throwable) {
                            config.logger
                                .log("Integration ${it.javaClass.name} failed to uninstall: $e.")
                        }
                    }
                }

                queue?.stop()

                featureFlagsSent.clear()
            } catch (e: Throwable) {
                config?.logger?.log("Close failed: $e.")
            }
        }
    }

    private var anonymousId: String
        get() {
            var anonymousId: String?
            synchronized(anonymousLock) {
                anonymousId = config?.cachePreferences?.getValue("anonymousId") as? String
                if (anonymousId == null) {
                    anonymousId = UUID.randomUUID().toString()
                    this.anonymousId = anonymousId ?: ""
                }
            }
            return anonymousId ?: ""
        }
        set(value) {
            config?.cachePreferences?.setValue("anonymousId", value)
        }

    private var distinctId: String
        get() {
            return config?.cachePreferences?.getValue(
                "distinctId",
                defaultValue = anonymousId,
            ) as? String ?: ""
        }
        set(value) {
            config?.cachePreferences?.setValue("distinctId", value)
        }

    private fun buildProperties(
        distinctId: String,
        properties: Map<String, Any>?,
        userProperties: Map<String, Any>?,
        userPropertiesSetOnce: Map<String, Any>?,
        groupProperties: Map<String, Any>?,
        appendSharedProps: Boolean = true,
    ): Map<String, Any> {
        val props = mutableMapOf<String, Any>()

        if (appendSharedProps) {
            val registeredPrefs = memoryPreferences.getAll()
            if (registeredPrefs.isNotEmpty()) {
                props.putAll(registeredPrefs)
            }

            config?.context?.getStaticContext()?.let {
                props.putAll(it)
            }

            config?.context?.getDynamicContext()?.let {
                props.putAll(it)
            }

            if (config?.sendFeatureFlagEvent == true) {
                featureFlags?.getFeatureFlags()?.let {
                    if (it.isNotEmpty()) {
                        val keys = mutableListOf<String>()
                        for (entry in it.entries) {
                            props["\$feature/${entry.key}"] = entry.value

                            // only add active feature flags
                            val active = entry.value as? Boolean ?: true

                            if (active) {
                                keys.add(entry.key)
                            }
                        }
                        props["\$active_feature_flags"] = keys
                    }
                }
            }
        }

        properties?.let {
            props.putAll(it)
        }

        userProperties?.let {
            props["\$set"] = it
        }

        userPropertiesSetOnce?.let {
            props["\$set_once"] = it
        }

        groupProperties?.let {
            props["\$groups"] = it
        }

        // only set if not there.
        props["distinct_id"]?.let {
            props["distinct_id"] = distinctId
        }

        return props
    }

    public override fun capture(
        event: String,
        distinctId: String?,
        properties: Map<String, Any>?,
        userProperties: Map<String, Any>?,
        userPropertiesSetOnce: Map<String, Any>?,
        groupProperties: Map<String, Any>?,
    ) {
        if (!isEnabled()) {
            return
        }
        if (config?.optOut == true) {
            config?.logger?.log("PostHog is in OptOut state.")
            return
        }

        val newDistinctId = distinctId ?: this.distinctId

        val postHogEvent = PostHogEvent(
            event,
            newDistinctId,
            properties = buildProperties(
                newDistinctId,
                properties,
                userProperties,
                userPropertiesSetOnce,
                groupProperties,
            ),
        )
        queue?.add(postHogEvent)
    }

    public override fun optIn() {
        if (!isEnabled()) {
            return
        }

        synchronized(lockOptOut) {
            config?.optOut = false
            config?.cachePreferences?.setValue("opt-out", false)
        }
    }

    public override fun optOut() {
        if (!isEnabled()) {
            return
        }

        synchronized(lockOptOut) {
            config?.optOut = true
            config?.cachePreferences?.setValue("opt-out", true)
        }
    }

    /**
     * Is Opt Out
     */
    public override fun isOptOut(): Boolean {
        if (!isEnabled()) {
            return true
        }
        return config?.optOut ?: true
    }

    public override fun screen(screenTitle: String, properties: Map<String, Any>?) {
        if (!isEnabled()) {
            return
        }

        val props = mutableMapOf<String, Any>()
        props["\$screen_name"] = screenTitle

        properties?.let {
            props.putAll(it)
        }

        capture("\$screen", properties = props)
    }

    public override fun alias(alias: String, properties: Map<String, Any>?) {
        if (!isEnabled()) {
            return
        }

        val props = mutableMapOf<String, Any>()
        props["alias"] = alias

        properties?.let {
            props.putAll(it)
        }

        capture("\$create_alias", properties = props)
    }

    public override fun identify(
        distinctId: String,
        properties: Map<String, Any>?,
        userProperties: Map<String, Any>?,
        userPropertiesSetOnce: Map<String, Any>?,
    ) {
        if (!isEnabled()) {
            return
        }

        val previousDistinctId = this.distinctId

        val props = mutableMapOf<String, Any>()
        props["\$anon_distinct_id"] = anonymousId
        props["distinct_id"] = distinctId

        properties?.let {
            props.putAll(it)
        }

        capture(
            "\$identify",
            distinctId = distinctId,
            properties = props,
            userProperties = userProperties,
            userPropertiesSetOnce = userPropertiesSetOnce,
        )

        if (previousDistinctId != distinctId) {
            // We keep the AnonymousId to be used by decide calls and identify to link the previousId
            this.anonymousId = previousDistinctId
            this.distinctId = distinctId

            // only because of testing in isolation, this flag is always enabled
            if (reloadFeatureFlags) {
                reloadFeatureFlags()
            }
        }
    }

    public override fun group(type: String, key: String, groupProperties: Map<String, Any>?) {
        if (!isEnabled()) {
            return
        }

        val props = mutableMapOf<String, Any>()
        props["\$group_type"] = type
        props["\$group_key"] = key
        groupProperties?.let {
            props["\$group_set"] = it
        }

        @Suppress("UNCHECKED_CAST")
        val groups = memoryPreferences.getValue("\$groups") as? Map<String, Any>
        val newGroups = mutableMapOf<String, Any>()
        var reloadFeatureFlags = false

        groups?.let {
            val currentKey = it[type]

            if (key != currentKey) {
                reloadFeatureFlags = true
            }

            newGroups.putAll(it)
        }
        newGroups[type] = key

        capture("\$groupidentify", properties = props)

        memoryPreferences.setValue("\$groups", newGroups)

        if (reloadFeatureFlags) {
            loadFeatureFlagsRequest(null)
        }
    }

    public override fun reloadFeatureFlags(onFeatureFlags: PostHogOnFeatureFlags?) {
        if (!isEnabled()) {
            return
        }
        loadFeatureFlagsRequest(onFeatureFlags)
    }

    private fun loadFeatureFlagsRequest(onFeatureFlags: PostHogOnFeatureFlags?) {
        val props = mutableMapOf<String, Any>()
        props["\$anon_distinct_id"] = anonymousId
        props["distinct_id"] = distinctId

        @Suppress("UNCHECKED_CAST")
        val groups = memoryPreferences.getValue("\$groups") as? Map<String, Any>

        featureFlags?.loadFeatureFlags(distinctId, anonymousId, groups, onFeatureFlags)
    }

    public override fun isFeatureEnabled(key: String, defaultValue: Boolean): Boolean {
        if (!isEnabled()) {
            return defaultValue
        }
        return featureFlags?.isFeatureEnabled(key, defaultValue) ?: defaultValue
    }

    public override fun getFeatureFlag(key: String, defaultValue: Any?): Any? {
        if (!isEnabled()) {
            return defaultValue
        }
        val flag = featureFlags?.getFeatureFlag(key, defaultValue) ?: defaultValue

        var shouldSendFeatureFlagEvent = true
        synchronized(featureFlagsSentLock) {
            if (featureFlagsSent.contains(key)) {
                shouldSendFeatureFlagEvent = false
            } else {
                featureFlagsSent.add(key)
            }
        }

        if (config?.sendFeatureFlagEvent == true && shouldSendFeatureFlagEvent) {
            val props = mutableMapOf<String, Any>()
            props["\$feature_flag"] = key
            flag?.let {
                props["\$feature_flag_response"] = it
            }

            capture("\$feature_flag_called", properties = props)
        }

        return flag
    }

    public override fun getFeatureFlagPayload(key: String, defaultValue: Any?): Any? {
        if (!isEnabled()) {
            return defaultValue
        }
        return featureFlags?.getFeatureFlagPayload(key, defaultValue) ?: defaultValue
    }

    public override fun flush() {
        if (!isEnabled()) {
            return
        }
        queue?.flush()
    }

    public override fun reset() {
        if (!isEnabled()) {
            return
        }

        memoryPreferences.clear()
        // only remove properties, preserve BUILD and VERSION keys in order to to fix over-sending
        // of 'Application Installed' events and under-sending of 'Application Updated' events
        config?.cachePreferences?.clear(except = listOf("build", "version"))
        featureFlags?.clear()
        queue?.clear()
        featureFlagsSent.clear()
    }

    private fun isEnabled(): Boolean {
        if (!enabled) {
            config?.logger?.log("Setup isn't called.")
        }
        return enabled
    }

    public override fun register(key: String, value: Any) {
        if (!isEnabled()) {
            return
        }
        memoryPreferences.setValue(key, value)
    }

    public override fun unregister(key: String) {
        if (!isEnabled()) {
            return
        }
        memoryPreferences.remove(key)
    }

    override fun distinctId(): String {
        if (!isEnabled()) {
            return ""
        }
        return distinctId
    }

    public companion object : PostHogInterface {
        private var shared: PostHogInterface = PostHog()
        private var defaultSharedInstance = shared

        private val apiKeys = mutableSetOf<String>()

        @PostHogVisibleForTesting
        public fun overrideSharedInstance(postHog: PostHogInterface) {
            shared = postHog
        }

        @PostHogVisibleForTesting
        public fun resetSharedInstance() {
            shared = defaultSharedInstance
        }

        /**
         * Setup the SDK and returns an instance that you can hold and pass it around
         * @param T the type of the Config
         * @property config the Config
         */
        public fun <T : PostHogConfig> with(config: T): PostHogInterface {
            val instance = PostHog()
            instance.setup(config)
            return instance
        }

        @PostHogVisibleForTesting
        internal fun <T : PostHogConfig> withInternal(
            config: T,
            queueExecutor: ExecutorService,
            featureFlagsExecutor: ExecutorService,
            cachedEventsExecutor: ExecutorService,
            reloadFeatureFlags: Boolean,
        ): PostHogInterface {
            val instance = PostHog(
                queueExecutor,
                featureFlagsExecutor,
                cachedEventsExecutor,
                reloadFeatureFlags = reloadFeatureFlags,
            )
            instance.setup(config)
            return instance
        }

        public override fun <T : PostHogConfig> setup(config: T) {
            shared.setup(config)
        }

        public override fun close() {
            shared.close()
        }

        public override fun capture(
            event: String,
            distinctId: String?,
            properties: Map<String, Any>?,
            userProperties: Map<String, Any>?,
            userPropertiesSetOnce: Map<String, Any>?,
            groupProperties: Map<String, Any>?,
        ) {
            shared.capture(
                event,
                distinctId = distinctId,
                properties = properties,
                userProperties = userProperties,
                userPropertiesSetOnce = userPropertiesSetOnce,
                groupProperties = groupProperties,
            )
        }

        public override fun identify(
            distinctId: String,
            properties: Map<String, Any>?,
            userProperties: Map<String, Any>?,
            userPropertiesSetOnce: Map<String, Any>?,
        ) {
            shared.identify(
                distinctId,
                properties = properties,
                userProperties = userProperties,
                userPropertiesSetOnce = userPropertiesSetOnce,
            )
        }

        public override fun reloadFeatureFlags(onFeatureFlags: PostHogOnFeatureFlags?) {
            shared.reloadFeatureFlags(onFeatureFlags)
        }

        public override fun isFeatureEnabled(key: String, defaultValue: Boolean): Boolean =
            shared.isFeatureEnabled(key, defaultValue = defaultValue)

        public override fun getFeatureFlag(key: String, defaultValue: Any?): Any? =
            shared.getFeatureFlag(key, defaultValue = defaultValue)

        public override fun getFeatureFlagPayload(key: String, defaultValue: Any?): Any? =
            shared.getFeatureFlagPayload(key, defaultValue = defaultValue)

        public override fun flush() {
            shared.flush()
        }

        public override fun reset() {
            shared.reset()
        }

        public override fun optIn() {
            shared.optIn()
        }

        public override fun optOut() {
            shared.optOut()
        }

        public override fun group(type: String, key: String, groupProperties: Map<String, Any>?) {
            shared.group(type, key, groupProperties = groupProperties)
        }

        public override fun screen(screenTitle: String, properties: Map<String, Any>?) {
            shared.screen(screenTitle, properties = properties)
        }

        public override fun alias(alias: String, properties: Map<String, Any>?) {
            shared.alias(alias, properties = properties)
        }

        public override fun isOptOut(): Boolean = shared.isOptOut()

        public override fun register(key: String, value: Any) {
            shared.register(key, value)
        }

        public override fun unregister(key: String) {
            shared.unregister(key)
        }

        override fun distinctId(): String = shared.distinctId()
    }
}

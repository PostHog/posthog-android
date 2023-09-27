package com.posthog

import com.posthog.internal.PostHogApi
import com.posthog.internal.PostHogFeatureFlags
import com.posthog.internal.PostHogMemoryPreferences
import com.posthog.internal.PostHogQueue
import com.posthog.internal.PostHogSendCachedEventsIntegration
import com.posthog.internal.PostHogSerializer
import java.util.Date
import java.util.UUID

public class PostHog private constructor() {
    @Volatile
    private var enabled = false

    private val lockSetup = Any()
    private val lockOptOut = Any()
    private val anonymousLock = Any()

    private var config: PostHogConfig? = null

    private var featureFlags: PostHogFeatureFlags? = null
    private var api: PostHogApi? = null
    private var queue: PostHogQueue? = null
    private var memoryPreferences = PostHogMemoryPreferences()

    public fun setup(config: PostHogConfig) {
        synchronized(lockSetup) {
            try {
                if (enabled) {
                    config.logger.log("Setup called despite already being setup!")
                    return
                }

                val cachePreferences = config.cachePreferences ?: PostHogMemoryPreferences()
                config.cachePreferences = cachePreferences
                val serializer = PostHogSerializer(config)
                val api = PostHogApi(config, serializer)
                val queue = PostHogQueue(config, api, serializer)
                val featureFlags = PostHogFeatureFlags(config, api)

                // no need to lock optOut here since the setup is locked already
                val optOut = config.cachePreferences?.getValue("opt-out", defaultValue = false) as? Boolean
                optOut?.let {
                    config.optOut = optOut
                }

                val startDate = Date()
                val sendCachedEventsIntegration = PostHogSendCachedEventsIntegration(config, api, serializer, startDate)

                this.api = api
                this.config = config
                this.queue = queue
                this.featureFlags = featureFlags
                enabled = true

                config.addIntegration(sendCachedEventsIntegration)

                legacyPreferences(config, serializer)

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
                serializer.deserializeCachedProperties(it)?.let { props ->
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

    public fun close() {
        synchronized(lockSetup) {
            try {
                enabled = false

                config?.integrations?.forEach {
                    try {
                        it.uninstall()
                    } catch (e: Throwable) {
                        config?.logger?.log("Integration ${it.javaClass.name} failed to uninstall: $e.")
                    }
                }

                queue?.stop()
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
            return config?.cachePreferences?.getValue("distinctId", defaultValue = anonymousId) as? String ?: ""
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

        properties?.let {
            props.putAll(it)
        }

        if (appendSharedProps) {
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
                            keys.add(entry.key)
                        }
                        props["\$active_feature_flags"] = keys
                    }
                }
            }

            val registeredPrefs = memoryPreferences.getAll()
            if (registeredPrefs.isNotEmpty()) {
                props.putAll(registeredPrefs)
            }
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

    // test: $merge_dangerously
    public fun capture(
        event: String,
        distinctId: String? = null,
        properties: Map<String, Any>? = null,
        userProperties: Map<String, Any>? = null,
        userPropertiesSetOnce: Map<String, Any>? = null,
        groupProperties: Map<String, Any>? = null,
    ) {
        if (!isEnabled()) {
            return
        }
        if (config?.optOut == true) {
            config?.logger?.log("PostHog is in OptOut state.")
            return
        }

        val newDistinctId = distinctId ?: this.distinctId

        val postHogEvent = PostHogEvent(event, newDistinctId, properties = buildProperties(newDistinctId, properties, userProperties, userPropertiesSetOnce, groupProperties))
        queue?.add(postHogEvent)
    }

    public fun optIn() {
        if (!isEnabled()) {
            return
        }

        synchronized(lockOptOut) {
            config?.optOut = false
            config?.cachePreferences?.setValue("opt-out", false)
        }
    }

    public fun optOut() {
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
    public fun isOptOut(): Boolean {
        if (!isEnabled()) {
            return true
        }
        return config?.optOut ?: true
    }

    public fun screen(screenTitle: String, properties: Map<String, Any>? = null) {
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

    public fun alias(alias: String, properties: Map<String, Any>? = null) {
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

    public fun identify(
        distinctId: String,
        properties: Map<String, Any>? = null,
        userProperties: Map<String, Any>? = null,
        userPropertiesSetOnce: Map<String, Any>? = null,
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

        capture("\$identify", distinctId = distinctId, properties = props, userProperties = userProperties, userPropertiesSetOnce = userPropertiesSetOnce)

        if (previousDistinctId != distinctId) {
            // We keep the AnonymousId to be used by decide calls and identify to link the previousId
            this.anonymousId = previousDistinctId
            this.distinctId = distinctId

            reloadFeatureFlagsRequest()
        }
    }

    public fun group(type: String, key: String, groupProperties: Map<String, Any>? = null) {
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
        memoryPreferences.setValue("\$groups", newGroups)

        capture("\$groupidentify", properties = props)

        if (reloadFeatureFlags) {
            loadFeatureFlagsRequest(null)
        }
    }

    public fun reloadFeatureFlagsRequest(onFeatureFlags: PostHogOnFeatureFlags? = null) {
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

        featureFlags?.loadFeatureFlags(buildProperties(distinctId, props, null, null, groups, appendSharedProps = false), onFeatureFlags)
    }

    public fun isFeatureEnabled(key: String, defaultValue: Boolean = false): Boolean {
        if (!isEnabled()) {
            return defaultValue
        }
        return featureFlags?.isFeatureEnabled(key, defaultValue) ?: defaultValue
    }

    public fun getFeatureFlag(key: String, defaultValue: Any? = null): Any? {
        if (!isEnabled()) {
            return defaultValue
        }
        val flag = featureFlags?.getFeatureFlag(key, defaultValue) ?: defaultValue

        if (config?.sendFeatureFlagEvent == true) {
            val props = mutableMapOf<String, Any>()
            props["\$feature_flag"] = key
            flag?.let {
                props["\$feature_flag_response"] = it
            }

            capture("\$feature_flag_called", properties = props)
        }

        return flag
    }

    public fun getFeatureFlagPayload(key: String, defaultValue: Any?): Any? {
        if (!isEnabled()) {
            return defaultValue
        }
        return featureFlags?.getFeatureFlagPayload(key, defaultValue) ?: defaultValue
    }

    public fun flush() {
        if (!isEnabled()) {
            return
        }
        queue?.flush()
    }

    public fun reset() {
        if (!isEnabled()) {
            return
        }

        memoryPreferences.clear(listOf())
        // only remove properties, preserve BUILD and VERSION keys in order to to fix over-sending
        // of 'Application Installed' events and under-sending of 'Application Updated' events
        config?.cachePreferences?.clear(listOf("build", "build"))
        featureFlags?.clear()
        queue?.clear()
    }

    private fun isEnabled(): Boolean {
        if (!enabled) {
            config?.logger?.log("Setup isn't called.")
        }
        return enabled
    }

    public fun register(key: String, value: Any) {
        if (!isEnabled()) {
            return
        }
        memoryPreferences.setValue(key, value)
    }

    public fun unregister(key: String) {
        if (!isEnabled()) {
            return
        }
        memoryPreferences.remove(key)
    }

    public companion object {
        private val shared: PostHog = PostHog()

        private val apiKeys = mutableSetOf<String>()

        public fun with(config: PostHogConfig): PostHog {
            logIfApiKeyExists(config)

            val instance = PostHog()
            instance.setup(config)
            return instance
        }

        private fun logIfApiKeyExists(config: PostHogConfig) {
            if (apiKeys.contains(config.apiKey)) {
                config.logger.log("API Key: ${config.apiKey} already has a PostHog instance.")
            }
        }

        public fun setup(config: PostHogConfig) {
            logIfApiKeyExists(config)

            shared.setup(config)
        }

        public fun close() {
            shared.config?.let {
                apiKeys.remove(it.apiKey)
            }
            shared.close()
        }

        public fun capture(
            event: String,
            distinctId: String? = null,
            properties: Map<String, Any>? = null,
            userProperties: Map<String, Any>? = null,
            userPropertiesSetOnce: Map<String, Any>? = null,
            groupProperties: Map<String, Any>? = null,
        ) {
            shared.capture(event, distinctId = distinctId, properties = properties, userProperties = userProperties, userPropertiesSetOnce = userPropertiesSetOnce, groupProperties = groupProperties)
        }

        public fun identify(
            distinctId: String,
            properties: Map<String, Any>? = null,
            userProperties: Map<String, Any>? = null,
            userPropertiesSetOnce: Map<String, Any>? = null,
        ) {
            shared.identify(distinctId, properties = properties, userProperties = userProperties, userPropertiesSetOnce = userPropertiesSetOnce)
        }

        public fun reloadFeatureFlagsRequest() {
            shared.reloadFeatureFlagsRequest()
        }

        public fun isFeatureEnabled(key: String, defaultValue: Boolean = false): Boolean {
            return shared.isFeatureEnabled(key, defaultValue = defaultValue)
        }

        public fun getFeatureFlag(key: String, defaultValue: Any? = null): Any? {
            return shared.getFeatureFlag(key, defaultValue = defaultValue)
        }

        public fun getFeatureFlagPayload(key: String, defaultValue: Any? = null): Any? {
            return shared.getFeatureFlagPayload(key, defaultValue = defaultValue)
        }

        public fun flush() {
            shared.flush()
        }

        public fun reset() {
            shared.reset()
        }

        public fun optIn() {
            shared.optIn()
        }

        public fun optOut() {
            shared.optOut()
        }

        public fun group(type: String, key: String, groupProperties: Map<String, Any>? = null) {
            shared.group(type, key, groupProperties = groupProperties)
        }

        public fun screen(screenTitle: String, properties: Map<String, Any>? = null) {
            shared.screen(screenTitle, properties = properties)
        }

        public fun alias(alias: String, properties: Map<String, Any>? = null) {
            shared.alias(alias, properties = properties)
        }

        public fun isOptOut(): Boolean {
            return shared.isOptOut()
        }

        public fun register(key: String, value: Any) {
            shared.register(key, value)
        }

        public fun unregister(key: String) {
            shared.unregister(key)
        }
    }
}

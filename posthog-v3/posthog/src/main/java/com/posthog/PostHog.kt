package com.posthog

import com.posthog.internal.PostHogApi
import com.posthog.internal.PostHogFeatureFlags
import com.posthog.internal.PostHogMemoryPreferences
import com.posthog.internal.PostHogQueue
import com.posthog.internal.PostHogSerializer
import com.posthog.internal.PostHogSessionManager
import com.posthog.internal.SendCachedEventsIntegration

public class PostHog private constructor() {
    @Volatile
    private var enabled = false

    private val lock = Any()

    private var config: PostHogConfig? = null
    private var sessionManager: PostHogSessionManager? = null
    private var featureFlags: PostHogFeatureFlags? = null
    private var api: PostHogApi? = null
    private var queue: PostHogQueue? = null

    // TODO: reachability

    public fun setup(config: PostHogConfig) {
        synchronized(lock) {
            if (enabled) {
                config.logger.log("Setup called despite already being setup!")
                return
            }

            val preferences = config.preferences ?: PostHogMemoryPreferences()
            config.preferences = preferences
            sessionManager = PostHogSessionManager(preferences)
            val serializer = PostHogSerializer(config)
            val api = PostHogApi(config, serializer)
            val queue = PostHogQueue(config, api, serializer)
            val featureFlags = PostHogFeatureFlags(config, api)

            val enable = config.preferences?.getValue("opt-out", defaultValue = config.enable) as? Boolean
            enable?.let {
                config.enable = enable
            }

            val sendCachedEventsIntegration = SendCachedEventsIntegration(config, api, serializer)

            this.api = api
            this.config = config
            this.queue = queue
            this.featureFlags = featureFlags
            enabled = true

            config.integrations.add(sendCachedEventsIntegration)

            config.integrations.forEach {
                it.install()
            }

            queue.start()

            if (config.preloadFeatureFlags) {
                loadFeatureFlagsRequest()
            }
        }
    }

    public fun close() {
        synchronized(lock) {
            enabled = false

            config?.integrations?.forEach {
                it.uninstall()
            }

            queue?.stop()
        }
    }

    public val anonymousId: String?
        get() {
            if (!isEnabled()) {
                return null
            }
            return sessionManager?.anonymousId
        }

    public val distinctId: String
        get() {
            if (!isEnabled()) {
                return ""
            }
            return sessionManager?.distinctId ?: ""
        }

    private fun buildProperties(distinctId: String, properties: Map<String, Any>?, userProperties: Map<String, Any>?, groupProperties: Map<String, Any>?): Map<String, Any> {
        val props = mutableMapOf<String, Any>()

        config?.context?.getStaticContext()?.let {
            props.putAll(it)
        }

        config?.context?.getDynamicContext()?.let {
            props.putAll(it)
        }

        // TODO: $feature/* properties, $active_feature_flags array

        // TODO: $set_once
        userProperties?.let {
            props["\$set"] = it
        }

        groupProperties?.let {
            props["\$groups"] = it
        }

        properties?.let {
            props.putAll(it)
        }

        // only set if not there.
        props["distinct_id"]?.let {
            props["distinct_id"] = distinctId
        }

        return props
    }

    // test: $merge_dangerously
    public fun capture(event: String, properties: Map<String, Any>? = null, userProperties: Map<String, Any>? = null, groupProperties: Map<String, Any>? = null) {
        if (!isEnabled()) {
            return
        }
        if (config?.enable == false) {
            config?.logger?.log("PostHog is in OptOut state.")
            return
        }

        val postHogEvent = PostHogEvent(event, distinctId, properties = buildProperties(distinctId, properties, userProperties, groupProperties))
        queue?.add(postHogEvent)
    }

    public fun optIn() {
        if (!isEnabled()) {
            return
        }

        config?.enable = true
        config?.preferences?.setValue("opt-out", true)
    }

    public fun optOut() {
        if (!isEnabled()) {
            return
        }

        config?.enable = false
        config?.preferences?.setValue("opt-out", false)
    }

//    public fun register(key: String, value: Any) {
//    }
//
//    public fun unregister(key: String) {
//    }

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

    public fun identify(distinctId: String, properties: Map<String, Any>? = null, userProperties: Map<String, Any>? = null) {
        if (!isEnabled()) {
            return
        }

        val oldDistinctId = this.distinctId

        val props = mutableMapOf<String, Any>()
        anonymousId?.let {
            props["\$anon_distinct_id"] = it
        }
        props["distinct_id"] = distinctId

        properties?.let {
            props.putAll(it)
        }

        capture("\$identify", properties = props, userProperties = userProperties)

        if (oldDistinctId != distinctId) {
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

        capture("\$groupidentify", properties = props)
    }

    public fun reloadFeatureFlagsRequest() {
        if (!isEnabled()) {
            return
        }
        loadFeatureFlagsRequest()
    }

    private fun loadFeatureFlagsRequest() {
        val map = mapOf<String, Any>()
        featureFlags?.loadFeatureFlags(buildProperties(distinctId, map, null, null))
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

    // TODO: If you also want to reset the device_id so that the device will be considered a new device in future events, you can pass true as an argument:
    // this does not exist on Android yet
    public fun reset() {
        if (!isEnabled()) {
            return
        }
        config?.preferences?.clear()
        queue?.clear()
    }

    private fun isEnabled(): Boolean {
        if (!enabled) {
            config?.logger?.log("Setup isn't called.")
        }
        return enabled
    }

    public companion object {
        // TODO: make it private and rely only on static methods that forward to shared?
        private val shared: PostHog = PostHog()

        private val apiKeys = mutableSetOf<String>()

        // TODO: understand why with was used to return the custom instance
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

        public fun capture(event: String, properties: Map<String, Any>? = null, userProperties: Map<String, Any>? = null) {
            shared.capture(event, properties = properties, userProperties = userProperties)
        }

        public fun identify(distinctId: String, properties: Map<String, Any>? = null, userProperties: Map<String, Any>? = null) {
            shared.identify(distinctId, properties = properties, userProperties = userProperties)
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

        // TODO: add other methods
    }
}

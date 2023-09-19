package com.posthog

import com.posthog.internal.PostHogApi
import com.posthog.internal.PostHogFeatureFlags
import com.posthog.internal.PostHogQueue
import com.posthog.internal.PostHogSerializer
import com.posthog.internal.PostHogSessionManager
import com.posthog.internal.PostHogStorage
import com.posthog.internal.SendCachedEventsIntegration

// TODO: register/unregister? (Super Property) https://posthog.com/docs/libraries/react-native

public class PostHog private constructor() {
    @Volatile
    private var enabled = false

    private val lock = Any()

    private var config: PostHogConfig? = null
    private var storage: PostHogStorage? = null
    private var sessionManager: PostHogSessionManager? = null
    private var featureFlags: PostHogFeatureFlags? = null
    private var api: PostHogApi? = null
    private var queue: PostHogQueue? = null
    private var context: PostHogContext? = null
    private var serializer: PostHogSerializer? = null

    // TODO: flushTimer, reachability, flagCallReported

    public fun setup(config: PostHogConfig) {
        synchronized(lock) {
            if (enabled) {
                println("Setup called despite already being setup!")
                return
            }

            val storage = PostHogStorage(config)
            sessionManager = PostHogSessionManager(storage)
            val serializer = PostHogSerializer(config)
            val api = PostHogApi(config, serializer)
            val queue = PostHogQueue(config, storage, api, serializer)
            val featureFlags = PostHogFeatureFlags(config, api)

            val sendCachedEventsIntegration = SendCachedEventsIntegration(config, api, serializer)

            this.api = api
            this.storage = storage
            this.config = config
            this.queue = queue
            this.featureFlags = featureFlags
            enabled = true

            config.integrations.add(sendCachedEventsIntegration)

            config.integrations.forEach {
                it.install()
            }

            queue.start()

            // TODO: guarded by preloadFeatureFlags
//            loadFeatureFlagsRequest()
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

    public val distinctId: String?
        get() {
            if (!isEnabled()) {
                return null
            }
            return sessionManager?.distinctId
        }

    private fun buildProperties(properties: Map<String, Any>?): Map<String, Any> {
        val props = mutableMapOf<String, Any>()

        properties?.let {
            props.putAll(it)
        }
        // TODO: message_id=messageId

        context?.getStaticContext()?.let {
            props.putAll(it)
        }

        context?.getDynamicContext()?.let {
            props.putAll(it)
        }

        // distinctId is always present but it has to be nullable because the SDK may be disabled
        distinctId?.let {
            props["distinct_id"] = it
        }

        return props
    }

    // TODO: optIn/enable?

    // test: $merge_dangerously
    public fun capture(event: String, properties: Map<String, Any>? = null) {
        if (!isEnabled()) {
            return
        }

        val postHogEvent = PostHogEvent(event, buildProperties(properties))
        queue?.add(postHogEvent)
    }

    public fun screen(screenTitle: String, properties: Map<String, Any>? = null) {
        if (!isEnabled()) {
            return
        }

        val props = mutableMapOf<String, Any>()
        props["\$screen_name"] = screenTitle

        properties?.let {
            // TODO: who has precedence here? the given props or the built ones
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

        // TODO: reset feature flags, set anonymousId and distinctId
//        val oldDistinctId = this.distinctId

        val props = mutableMapOf<String, Any>()
        props["distinct_id"] = distinctId
        anonymousId?.let {
            props["\$anon_distinct_id"] = it
        }
        properties?.let {
            props.putAll(it)
        }
        userProperties?.let {
            // Should $set be its own data class?
            props["\$set"] = it
        }

        // TODO: should $set_once also exist? it does not exist on Android

        capture("\$identify", properties = props)
    }

    public fun group(type: String, key: String, properties: Map<String, Any>? = null) {
        if (!isEnabled()) {
            return
        }
        // TODO: groupProperties, event $groupidentify
    }

    public fun reloadFeatureFlagsRequest() {
        if (!isEnabled()) {
            return
        }
        loadFeatureFlagsRequest()
    }

    private fun loadFeatureFlagsRequest() {
        val map = mapOf<String, Any>()
        featureFlags?.loadFeatureFlags(buildProperties(map))
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

        // TODO: reportFeatureFlagCalled, guarded by sendFeatureFlagEvent

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
        // TODO: reset stuff, delete cache
    }

    // TODO: groups, groupIdentify, group, feature flags, buildProperties (static context, dynamic context, distinct_id)

    private fun isEnabled(): Boolean {
        if (!enabled) {
            config?.logger?.log("Setup isn't called.")
        }
        return enabled
    }

    public companion object {
        // TODO: make it private and rely only on static methods that forward to shared?
        private val shared: PostHog = PostHog()

        // TODO: understand why with was used to return the custom instance
        public fun with(config: PostHogConfig): PostHog {
            val instance = PostHog()
            instance.setup(config)
            return instance
        }

        public fun setup(config: PostHogConfig) {
            shared.setup(config)
        }

        public fun close() {
            shared.close()
        }

        public fun capture(event: String, properties: Map<String, Any>? = null) {
            shared.capture(event, properties = properties)
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

        // TODO: add other methods

        // DISCUSS: Middleware, what does it stand for?
    }
}

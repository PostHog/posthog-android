package com.posthog

import com.posthog.internal.PostHogApi
import com.posthog.internal.PostHogFeatureFlags
import com.posthog.internal.PostHogPrintLogger
import com.posthog.internal.PostHogQueue
import com.posthog.internal.PostHogSessionManager
import com.posthog.internal.PostHogStorage

public class PostHog {
    @Volatile
    private var enabled = false

    private val lock = Any()

    private var config: PostHogConfig? = null
    private var storage: PostHogStorage? = null
    private var sessionManager: PostHogSessionManager? = null
    private var featureFlags: PostHogFeatureFlags? = null
    private var api: PostHogApi? = null
    private var queue: PostHogQueue? = null

    private val retryDelay = 5.0
    private val maxRetryDelay = 30.0
    // TODO: flushTimer, reachability, retryCount, flagCallReported

    public fun setup(config: PostHogConfig) {
        synchronized(lock) {
            if (enabled) {
                println("Setup called despite already being setup!")
                return
            }

            // TODO: would be possible to toggle debug at runtime or during init only?
            config.logger = config.logger ?: PostHogPrintLogger(config.debug)

            val storage = PostHogStorage(config)
            sessionManager = PostHogSessionManager(storage)
            val api = PostHogApi(config)
            val queue = PostHogQueue(config, storage, api)
            val featureFlags = PostHogFeatureFlags(config, api)

            this.api = api
            this.storage = storage
            this.config = config
            this.queue = queue
            this.featureFlags = featureFlags
            enabled = true

            queue.start()
            loadFeatureFlagsRequest()
        }
    }

    public fun close() {
        synchronized(lock) {
            enabled = false
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

        // distinctId is always present but it has to be nullable because the SDK may be disabled
        distinctId?.let {
            props["distinct_id"] = it
        }

        return props
    }

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

    public fun reloadFeatureFlagsRequest() {
        if (!isEnabled()) {
            return
        }
        loadFeatureFlagsRequest()
    }

    private fun loadFeatureFlagsRequest() {
        val map = mapOf<String, Any>()
        featureFlags?.loadFeatureFlagsRequest(buildProperties(map))
    }

    public fun isFeatureEnabled(key: String, defaultValue: Boolean = false): Boolean {
        if (!isEnabled()) {
            return defaultValue
        }
        return featureFlags?.isFeatureEnabled(key, defaultValue) ?: defaultValue
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

        public fun reloadFeatureFlagsRequest() {
            shared.reloadFeatureFlagsRequest()
        }

        public fun isFeatureEnabled(key: String, defaultValue: Boolean = false): Boolean {
            return shared.isFeatureEnabled(key, defaultValue = defaultValue)
        }

        // TODO: add other methods
    }
}

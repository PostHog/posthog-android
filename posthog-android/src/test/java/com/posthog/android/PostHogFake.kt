package com.posthog.android

import com.posthog.PostHogConfig
import com.posthog.PostHogInterface
import com.posthog.PostHogOnFeatureFlags

public class PostHogFake : PostHogInterface {
    public var event: String? = null
    public var screenTitle: String? = null
    public var properties: Map<String, Any>? = null

    override fun <T : PostHogConfig> setup(config: T) {
        TODO("Not yet implemented")
    }

    override fun close() {
        TODO("Not yet implemented")
    }

    override fun capture(
        event: String,
        distinctId: String?,
        properties: Map<String, Any>?,
        userProperties: Map<String, Any>?,
        userPropertiesSetOnce: Map<String, Any>?,
        groupProperties: Map<String, Any>?,
    ) {
        this.event = event
        this.properties = properties
    }

    override fun identify(
        distinctId: String,
        properties: Map<String, Any>?,
        userProperties: Map<String, Any>?,
        userPropertiesSetOnce: Map<String, Any>?,
    ) {
        TODO("Not yet implemented")
    }

    override fun reloadFeatureFlagsRequest(onFeatureFlags: PostHogOnFeatureFlags?) {
        TODO("Not yet implemented")
    }

    override fun isFeatureEnabled(key: String, defaultValue: Boolean): Boolean {
        TODO("Not yet implemented")
    }

    override fun getFeatureFlag(key: String, defaultValue: Any?): Any? {
        TODO("Not yet implemented")
    }

    override fun getFeatureFlagPayload(key: String, defaultValue: Any?): Any? {
        TODO("Not yet implemented")
    }

    override fun flush() {
        TODO("Not yet implemented")
    }

    override fun reset() {
        TODO("Not yet implemented")
    }

    override fun optIn() {
        TODO("Not yet implemented")
    }

    override fun optOut() {
        TODO("Not yet implemented")
    }

    override fun group(type: String, key: String, groupProperties: Map<String, Any>?) {
        TODO("Not yet implemented")
    }

    override fun screen(screenTitle: String, properties: Map<String, Any>?) {
        this.screenTitle = screenTitle
    }

    override fun alias(alias: String, properties: Map<String, Any>?) {
        TODO("Not yet implemented")
    }

    override fun isOptOut(): Boolean {
        TODO("Not yet implemented")
    }

    override fun register(key: String, value: Any) {
        TODO("Not yet implemented")
    }

    override fun unregister(key: String) {
        TODO("Not yet implemented")
    }
}

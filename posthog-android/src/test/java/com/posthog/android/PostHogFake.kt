package com.posthog.android

import com.posthog.PostHogConfig
import com.posthog.PostHogInterface
import com.posthog.PostHogOnFeatureFlags
import java.util.Date
import java.util.UUID

public class PostHogFake : PostHogInterface {
    public var event: String? = null
    public var screenTitle: String? = null
    public var properties: Map<String, Any>? = null
    public var captures: Int = 0

    override fun <T : PostHogConfig> setup(config: T) {
    }

    override fun close() {
    }

    override fun capture(
        event: String,
        distinctId: String?,
        properties: Map<String, Any>?,
        userProperties: Map<String, Any>?,
        userPropertiesSetOnce: Map<String, Any>?,
        groups: Map<String, String>?,
        timestamp: Date?,
    ) {
        this.event = event
        this.properties = properties
        captures++
    }

    override fun captureException(
        throwable: Throwable,
        properties: Map<String, Any>?,
    ) {
    }

    override fun identify(
        distinctId: String,
        userProperties: Map<String, Any>?,
        userPropertiesSetOnce: Map<String, Any>?,
    ) {
    }

    override fun reloadFeatureFlags(onFeatureFlags: PostHogOnFeatureFlags?) {
    }

    override fun isFeatureEnabled(
        key: String,
        defaultValue: Boolean,
    ): Boolean {
        return false
    }

    override fun getFeatureFlag(
        key: String,
        defaultValue: Any?,
    ): Any? {
        return null
    }

    override fun getFeatureFlagPayload(
        key: String,
        defaultValue: Any?,
    ): Any? {
        return null
    }

    override fun flush() {
    }

    override fun setPersonPropertiesForFlags(
        userProperties: Map<String, Any>,
        reloadFeatureFlags: Boolean,
    ) {
    }

    override fun resetPersonPropertiesForFlags(reloadFeatureFlags: Boolean) {
    }

    override fun setGroupPropertiesForFlags(
        type: String,
        groupProperties: Map<String, Any>,
        reloadFeatureFlags: Boolean,
    ) {
    }

    override fun resetGroupPropertiesForFlags(
        type: String?,
        reloadFeatureFlags: Boolean,
    ) {
    }

    override fun reset() {
    }

    override fun optIn() {
    }

    override fun optOut() {
    }

    override fun group(
        type: String,
        key: String,
        groupProperties: Map<String, Any>?,
    ) {
    }

    override fun screen(
        screenTitle: String,
        properties: Map<String, Any>?,
    ) {
        this.screenTitle = screenTitle
    }

    override fun alias(alias: String) {
    }

    override fun isOptOut(): Boolean {
        return false
    }

    override fun register(
        key: String,
        value: Any,
    ) {
    }

    override fun unregister(key: String) {
    }

    override fun distinctId(): String {
        return ""
    }

    override fun debug(enable: Boolean) {
    }

    override fun startSession() {
    }

    override fun endSession() {
    }

    override fun isSessionActive(): Boolean {
        return false
    }

    override fun isSessionReplayActive(): Boolean {
        return false
    }

    override fun startSessionReplay(resumeCurrent: Boolean) {
    }

    override fun stopSessionReplay() {
    }

    override fun getSessionId(): UUID? {
        return null
    }

    override fun <T : PostHogConfig> getConfig(): T? {
        return null
    }
}

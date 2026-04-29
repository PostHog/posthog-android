package com.posthog.server

import com.posthog.FeatureFlagResult
import com.posthog.PostHogStateless
import com.posthog.internal.FeatureFlag
import com.posthog.server.internal.EvaluationsHost
import com.posthog.server.internal.PostHogFeatureFlags

@Suppress("DEPRECATION")
public class PostHog : PostHogStateless(), PostHogInterface {
    @Volatile
    private var serverConfig: PostHogConfig? = null

    private val evaluationsHost: EvaluationsHost =
        object : EvaluationsHost {
            override val warningsEnabled: Boolean
                get() = serverConfig?.featureFlagsLogWarnings ?: true

            override fun captureFeatureFlagCalled(
                distinctId: String,
                key: String,
                value: Any?,
                properties: Map<String, Any>,
            ) {
                if (getConfig<com.posthog.PostHogConfig>()?.sendFeatureFlagEvent == false) return
                this@PostHog.captureFeatureFlagCalledEvent(distinctId, key, value, properties)
            }

            override fun logWarning(message: String) {
                getConfig<com.posthog.PostHogConfig>()?.logger?.log(message)
            }
        }

    override fun <T : PostHogConfig> setup(config: T) {
        serverConfig = config
        super.setup(config.asCoreConfig())
    }

    override fun close() {
        super.close()
    }

    override fun identify(
        distinctId: String,
        userProperties: Map<String, Any>?,
        userPropertiesSetOnce: Map<String, Any>?,
    ) {
        super<PostHogStateless>.identify(
            distinctId,
            userProperties,
            userPropertiesSetOnce,
        )
    }

    override fun flush() {
        super.flush()
    }

    override fun debug(enable: Boolean) {
        super.debug(enable)
    }

    override fun capture(
        distinctId: String,
        event: String,
        properties: Map<String, Any>?,
        userProperties: Map<String, Any>?,
        userPropertiesSetOnce: Map<String, Any>?,
        groups: Map<String, String>?,
        timestamp: java.util.Date?,
        appendFeatureFlags: Boolean,
        flags: PostHogFeatureFlagEvaluations?,
    ) {
        val mergedProperties =
            when {
                flags != null -> {
                    if (appendFeatureFlags) {
                        getConfig<com.posthog.PostHogConfig>()?.logger?.log(
                            "capture() received both `flags` and `appendFeatureFlags=true`; " +
                                "using the supplied snapshot and skipping the redundant /flags fetch.",
                        )
                    }
                    mergeFeatureFlagPropertiesFromSnapshot(properties, flags)
                }
                appendFeatureFlags -> {
                    getConfig<com.posthog.PostHogConfig>()?.logger?.log(
                        "DEPRECATION: capture(appendFeatureFlags = true) is deprecated and will be " +
                            "removed in the next major. Call evaluateFlags(distinctId) once and pass the " +
                            "snapshot via capture(flags = …) instead — that path attaches " +
                            "\$feature/<key> properties without a redundant /flags request and lets you " +
                            "scope which flags to attach via flags.onlyAccessed() or flags.only(...).",
                    )
                    mergeFeatureFlagProperties(
                        distinctId = distinctId,
                        groups = groups,
                        userProperties = userProperties,
                        groupProperties = null,
                        properties = properties,
                    )
                }
                else -> properties
            }

        super.captureStateless(
            event,
            distinctId,
            mergedProperties,
            userProperties,
            userPropertiesSetOnce,
            groups,
            timestamp,
        )
    }

    @Deprecated(
        message = "Prefer evaluateFlags(distinctId).isEnabled(key). Will be removed in the next major.",
    )
    override fun isFeatureEnabled(
        distinctId: String,
        key: String,
        defaultValue: Boolean,
        groups: Map<String, String>?,
        personProperties: Map<String, Any?>?,
        groupProperties: Map<String, Map<String, Any?>>?,
    ): Boolean {
        return super.isFeatureEnabledStateless(
            distinctId,
            key,
            defaultValue,
            groups,
            personProperties,
            groupProperties,
        )
    }

    @Deprecated(
        message = "Prefer evaluateFlags(distinctId).getFlag(key). Will be removed in the next major.",
    )
    override fun getFeatureFlag(
        distinctId: String,
        key: String,
        defaultValue: Any?,
        groups: Map<String, String>?,
        personProperties: Map<String, Any?>?,
        groupProperties: Map<String, Map<String, Any?>>?,
    ): Any? {
        return super.getFeatureFlagStateless(
            distinctId,
            key,
            defaultValue,
            groups,
            personProperties,
            groupProperties,
        )
    }

    @Deprecated(
        message = "Prefer evaluateFlags(distinctId).getFlagPayload(key). Will be removed in the next major.",
    )
    override fun getFeatureFlagPayload(
        distinctId: String,
        key: String,
        defaultValue: Any?,
        groups: Map<String, String>?,
        personProperties: Map<String, Any?>?,
        groupProperties: Map<String, Map<String, Any?>>?,
    ): Any? {
        return super.getFeatureFlagPayloadStateless(
            distinctId,
            key,
            defaultValue,
            groups,
            personProperties,
            groupProperties,
        )
    }

    @Deprecated(
        message =
            "Prefer evaluateFlags(distinctId) and read flag values + payload from the snapshot. " +
                "Will be removed in the next major.",
    )
    override fun getFeatureFlagResult(
        distinctId: String,
        key: String,
        groups: Map<String, String>?,
        personProperties: Map<String, Any?>?,
        groupProperties: Map<String, Map<String, Any?>>?,
        sendFeatureFlagEvent: Boolean?,
    ): FeatureFlagResult? {
        return super.getFeatureFlagResultStateless(
            distinctId,
            key,
            groups,
            personProperties,
            groupProperties,
            sendFeatureFlagEvent,
        )
    }

    override fun group(
        distinctId: String,
        type: String,
        key: String,
        groupProperties: Map<String, Any>?,
    ) {
        super.groupStateless(
            distinctId,
            type,
            key,
            groupProperties,
        )
    }

    override fun alias(
        distinctId: String,
        alias: String,
    ) {
        super.aliasStateless(
            distinctId,
            alias,
        )
    }

    override fun reloadFeatureFlags() {
        (featureFlags as? PostHogFeatureFlags)?.loadFeatureFlagDefinitions()
    }

    override fun captureException(
        exception: Throwable,
        distinctId: String?,
        properties: Map<String, Any>?,
    ) {
        super.captureExceptionStateless(
            exception,
            distinctId = distinctId,
            properties = properties,
        )
    }

    private fun mergeFeatureFlagProperties(
        distinctId: String,
        groups: Map<String, String>?,
        userProperties: Map<String, Any>?,
        groupProperties: Map<String, Map<String, Any>>?,
        properties: Map<String, Any>?,
    ): Map<String, Any> {
        val flags =
            (featureFlags as? PostHogFeatureFlags)?.getFeatureFlags(
                distinctId = distinctId,
                groups = groups,
                groupProperties = groupProperties,
                personProperties = userProperties,
            )
        return appendFlagPropertiesFromMap(properties, flags)
    }

    private fun mergeFeatureFlagPropertiesFromSnapshot(
        properties: Map<String, Any>?,
        snapshot: PostHogFeatureFlagEvaluations,
    ): Map<String, Any> {
        return appendFlagPropertiesFromMap(properties, snapshot.flags)
    }

    private fun appendFlagPropertiesFromMap(
        properties: Map<String, Any>?,
        flags: Map<String, FeatureFlag>?,
    ): Map<String, Any> {
        val props = properties?.toMutableMap() ?: mutableMapOf()
        if (flags.isNullOrEmpty()) {
            return props
        }

        val activeFlags = mutableListOf<String>()
        for ((key, flag) in flags) {
            val flagValue: Any = flag.variant ?: flag.enabled
            props["\$feature/$key"] = flagValue
            val isActive =
                when (flagValue) {
                    is Boolean -> flagValue
                    is String -> flagValue.isNotEmpty()
                    else -> true
                }
            if (isActive) {
                activeFlags.add(key)
            }
        }
        props["\$active_feature_flags"] = activeFlags

        return props
    }

    override fun evaluateFlags(
        distinctId: String,
        groups: Map<String, String>?,
        personProperties: Map<String, Any?>?,
        groupProperties: Map<String, Map<String, Any?>>?,
        flagKeys: List<String>?,
        onlyEvaluateLocally: Boolean,
        disableGeoip: Boolean,
    ): PostHogFeatureFlagEvaluations {
        if (distinctId.isBlank()) {
            return PostHogFeatureFlagEvaluations.empty(evaluationsHost)
        }

        val featureFlagsImpl =
            featureFlags as? PostHogFeatureFlags
                ?: return PostHogFeatureFlagEvaluations.empty(evaluationsHost)

        val result =
            featureFlagsImpl.evaluateFlags(
                distinctId = distinctId,
                groups = groups,
                personProperties = personProperties,
                groupProperties = groupProperties,
                flagKeys = flagKeys,
                onlyEvaluateLocally = onlyEvaluateLocally,
                disableGeoip = disableGeoip,
            )

        return PostHogFeatureFlagEvaluations(
            distinctId = distinctId,
            flagMap = result.flags,
            locallyEvaluated = result.locallyEvaluated,
            requestId = result.requestId,
            evaluatedAt = result.evaluatedAt,
            definitionsLoadedAt = result.definitionsLoadedAt,
            responseError = result.responseError,
            host = evaluationsHost,
        )
    }

    public companion object {
        /**
         * Set up the SDK and returns an instance that you can hold and pass it around
         * @param T the type of the Config
         * @property config the Config
         */
        @JvmStatic
        public fun <T : PostHogConfig> with(config: T): PostHogInterface {
            val instance = PostHog()
            instance.setup(config)
            return instance
        }
    }
}

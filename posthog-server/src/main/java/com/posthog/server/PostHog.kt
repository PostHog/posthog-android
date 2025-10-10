package com.posthog.server

import com.posthog.PostHogStateless
import com.posthog.server.internal.EvaluationSource
import com.posthog.server.internal.FeatureFlagResultContext
import com.posthog.server.internal.PostHogFeatureFlags

public class PostHog : PostHogInterface, PostHogStateless() {
    private var serverConfig: PostHogConfig? = null

    override fun <T : PostHogConfig> setup(config: T) {
        super.setup(config.asCoreConfig())
        this.serverConfig = config
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
        sendFeatureFlags: PostHogSendFeatureFlagOptions?,
    ) {
        val updatedProperties =
            if (sendFeatureFlags == null) {
                properties
            } else {
                mutableMapOf<String, Any>().apply {
                    properties?.let { putAll(it) }
                }.also { props ->
                    appendFlagCaptureProperties(
                        distinctId,
                        props,
                        groups,
                        sendFeatureFlags,
                    )
                }
            }
        super.captureStateless(
            event,
            distinctId,
            updatedProperties,
            userProperties,
            userPropertiesSetOnce,
            groups,
            timestamp,
        )
    }

    override fun isFeatureEnabled(
        distinctId: String,
        key: String,
        defaultValue: Boolean,
        groups: Map<String, String>?,
        personProperties: Map<String, Any?>?,
        groupProperties: Map<String, Map<String, Any?>>?,
    ): Boolean {
        (featureFlags as? PostHogFeatureFlags)?.let { featureFlags ->
            val result =
                featureFlags.resolveFeatureFlag(
                    key,
                    distinctId,
                    groups,
                    personProperties,
                    groupProperties,
                )
            sendFeatureFlagCalled(
                distinctId,
                key,
                result,
            )
            val flag = result?.results?.get(key)
            return flag?.enabled ?: defaultValue
        }
        return defaultValue
    }

    override fun getFeatureFlag(
        distinctId: String,
        key: String,
        defaultValue: Any?,
        groups: Map<String, String>?,
        personProperties: Map<String, Any?>?,
        groupProperties: Map<String, Map<String, Any?>>?,
    ): Any? {
        (featureFlags as? PostHogFeatureFlags)?.let { featureFlags ->
            val result =
                featureFlags.resolveFeatureFlag(
                    key,
                    distinctId,
                    groups,
                    personProperties,
                    groupProperties,
                )
            sendFeatureFlagCalled(
                distinctId,
                key,
                result,
            )
            val flag = result?.results?.get(key)
            return flag?.variant ?: flag?.enabled ?: defaultValue
        }
        return defaultValue
    }

    override fun getFeatureFlagPayload(
        distinctId: String,
        key: String,
        defaultValue: Any?,
        groups: Map<String, String>?,
        personProperties: Map<String, Any?>?,
        groupProperties: Map<String, Map<String, Any?>>?,
    ): Any? {
        (featureFlags as? PostHogFeatureFlags)?.let { featureFlags ->
            val result =
                featureFlags.resolveFeatureFlag(
                    key,
                    distinctId,
                    groups,
                    personProperties,
                    groupProperties,
                )
            sendFeatureFlagCalled(
                distinctId,
                key,
                result,
            )
            val flag = result?.results?.get(key)
            return flag?.metadata?.payload ?: defaultValue
        }
        return defaultValue
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

    private fun sendFeatureFlagCalled(
        distinctId: String,
        key: String,
        resultContext: FeatureFlagResultContext?,
    ) {
        if (serverConfig?.sendFeatureFlagEvent == false || distinctId.isEmpty() || key.isEmpty() || resultContext == null) {
            return
        }

        if (config?.sendFeatureFlagEvent == true) {
            val requestedFlag = resultContext.results?.get(key)
            val requestedFlagValue = requestedFlag?.variant ?: requestedFlag?.enabled
            val isNewlySeen = featureFlagsCalled?.add(distinctId, key, requestedFlagValue) ?: false
            if (isNewlySeen) {
                val props = mutableMapOf<String, Any>()
                props["\$feature_flag"] = key
                props["\$feature_flag_response"] = requestedFlagValue ?: ""
                resultContext.requestId?.let {
                    props["\$feature_flag_request_id"] = it
                }
                requestedFlag?.metadata?.let {
                    props["\$feature_flag_id"] = it.id
                    props["\$feature_flag_version"] = it.version
                }
                props["\$feature_flag_reason"] = requestedFlag?.reason?.description ?: ""
                resultContext.source?.let {
                    props["\$feature_flag_source"] = it.toString()
                    if (it == EvaluationSource.LOCAL) {
                        props["locally_evaluated"] = true
                    }
                }

                var allFlags = resultContext.results
                if (!resultContext.exhaustive) {
                    // we only have partial results so we'll need to resolve the rest
                    resultContext.parameters?.let { params ->
                        // this will be cached or evaluated locally
                        val response =
                            (featureFlags as? PostHogFeatureFlags)?.resolveFeatureFlags(
                                distinctId,
                                params.groups,
                                params.personProperties,
                                params.groupProperties,
                                params.onlyEvaluateLocally,
                            )
                        if (response != null) {
                            allFlags = response.results
                        }
                    }
                }

                allFlags?.let { flags ->
                    val activeFeatureFlags = mutableListOf<String>()
                    flags.values.forEach { flag ->
                        val flagValue = flag.variant ?: flag.enabled
                        props["\$feature/${flag.key}"] = flagValue
                        if (flagValue != false) {
                            activeFeatureFlags.add(flag.key)
                        }
                    }
                    props["\$active_feature_flags"] = activeFeatureFlags.toList()
                }

                captureStateless("\$feature_flag_called", distinctId, properties = props)
            }
        }
    }

    private fun appendFlagCaptureProperties(
        distinctId: String,
        properties: MutableMap<String, Any>?,
        groups: Map<String, String>?,
        options: PostHogSendFeatureFlagOptions?,
    ) {
        if (options == null || properties == null) {
            return
        }

        val response =
            (featureFlags as? PostHogFeatureFlags)?.resolveFeatureFlags(
                distinctId,
                groups,
                options.personProperties,
                options.groupProperties,
                options.onlyEvaluateLocally,
            )

        response?.results?.values?.let {
            val activeFeatureFlags = mutableListOf<String>()
            it.forEach { flag ->
                val flagValue = flag.variant ?: flag.enabled
                properties["\$feature/${flag.key}"] = flagValue
                if (flagValue != false) {
                    activeFeatureFlags.add(flag.key)
                }
            }
            properties["\$active_feature_flags"] = activeFeatureFlags.toList()
        }
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

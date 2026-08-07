package com.posthog.server

import com.posthog.FeatureFlagResult
import com.posthog.PostHogEventName
import com.posthog.PostHogStateless
import com.posthog.errortracking.PostHogErrorTrackingAutoCaptureIntegration
import com.posthog.internal.FeatureFlag
import com.posthog.server.internal.EvaluationsHost
import com.posthog.server.internal.PostHogFeatureFlags

@Suppress("DEPRECATION")
public class PostHog : PostHogStateless(), PostHogInterface {
    private val evaluationsHost: EvaluationsHost =
        object : EvaluationsHost {
            override fun captureFeatureFlagCalled(
                distinctId: String,
                key: String,
                value: Any?,
                properties: Map<String, Any>,
                groups: Map<String, String>?,
            ) {
                if (getConfig<com.posthog.PostHogConfig>()?.sendFeatureFlagEvent == false) return
                this@PostHog.captureFeatureFlagCalledEvent(distinctId, key, value, properties, groups)
            }

            override fun logWarning(message: String) {
                getConfig<com.posthog.PostHogConfig>()?.logger?.log(message)
            }
        }

    /**
     * Uncaught-exception integration installed when [PostHogConfig.captureUncaughtExceptions] is
     * enabled, retained so it can be uninstalled on [close].
     */
    private var uncaughtExceptionIntegration: PostHogErrorTrackingAutoCaptureIntegration? = null

    override fun <T : PostHogConfig> setup(config: T) {
        // The base keeps its original state when it rejects a setup (already set up, or an invalid
        // config), so only wire anything on top when THIS call is the one that enabled the client —
        // otherwise a second setup() could install a handler bound to a config the base discarded.
        val alreadySetUp = isEnabled()
        super.setup(config.asCoreConfig())
        if (alreadySetUp || !isEnabled()) {
            return
        }

        // Core setup never installs integrations for the stateless base, so wire the uncaught
        // handler explicitly. Gate purely on the local server flag — the server SDK never fetches
        // remote config, so the remote-config gate the Android SDK uses can never fire here.
        // Single-owner by design: the handler is process-wide, so only the first client that opts in
        // installs it. With several live clients all opting in, closing the owner restores the
        // previous handler and the remaining clients do not take over — capture stops until a client
        // is set up again. Server apps use one client per process, so we don't ref-count here.
        if (config.captureUncaughtExceptions) {
            getConfig<com.posthog.PostHogConfig>()?.let { coreConfig ->
                val integration = PostHogErrorTrackingAutoCaptureIntegration(coreConfig) { true }
                // The uncaught Throwable is a PostHogThrowable carrying fatal/handled=false/mechanism;
                // routing it through captureException preserves those via the shared coercer, and the
                // queue sends fatal exception events synchronously on the crashing thread.
                integration.installWith(
                    object : PostHogErrorTrackingAutoCaptureIntegration.CaptureTarget {
                        override fun capture(throwable: Throwable) {
                            captureException(throwable)
                        }

                        override fun flush() {
                            this@PostHog.flush()
                        }
                    },
                )
                uncaughtExceptionIntegration = integration
            }
        }
    }

    override fun close() {
        uncaughtExceptionIntegration?.uninstall()
        uncaughtExceptionIntegration = null
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
        distinctId: String?,
        event: String,
        properties: Map<String, Any>?,
        userProperties: Map<String, Any>?,
        userPropertiesSetOnce: Map<String, Any>?,
        groups: Map<String, String>?,
        timestamp: java.util.Date?,
        appendFeatureFlags: Boolean,
        flags: PostHogFeatureFlagEvaluations?,
    ) {
        val captureContext = PostHogRequestContext.resolveCaptureContext(distinctId, properties)
        val mergedProperties =
            mergeCaptureProperties(
                distinctId = captureContext.distinctId,
                properties = captureContext.properties,
                userProperties = userProperties,
                groups = groups,
                appendFeatureFlags = appendFeatureFlags,
                flags = flags,
            )

        super.captureStateless(
            event,
            captureContext.distinctId,
            mergedProperties,
            userProperties,
            userPropertiesSetOnce,
            groups,
            timestamp,
        )
    }

    /**
     * Applies the shared capture-options merging semantics: a pre-evaluated [flags] snapshot wins,
     * otherwise [appendFeatureFlags] triggers a (deprecated) flag evaluation, otherwise
     * [properties] pass through unchanged.
     */
    private fun mergeCaptureProperties(
        distinctId: String,
        properties: Map<String, Any>?,
        userProperties: Map<String, Any>?,
        groups: Map<String, String>?,
        appendFeatureFlags: Boolean,
        flags: PostHogFeatureFlagEvaluations?,
    ): Map<String, Any>? =
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
        val resolvedDistinctId = PostHogRequestContext.resolveDistinctId(distinctId) ?: distinctId
        return super.isFeatureEnabledStateless(
            resolvedDistinctId,
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
        val resolvedDistinctId = PostHogRequestContext.resolveDistinctId(distinctId) ?: distinctId
        return super.getFeatureFlagStateless(
            resolvedDistinctId,
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
        val resolvedDistinctId = PostHogRequestContext.resolveDistinctId(distinctId) ?: distinctId
        return super.getFeatureFlagPayloadStateless(
            resolvedDistinctId,
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
        val resolvedDistinctId = PostHogRequestContext.resolveDistinctId(distinctId) ?: distinctId
        return super.getFeatureFlagResultStateless(
            resolvedDistinctId,
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
        if (!enabled) {
            super.captureExceptionStateless(
                exception,
                distinctId = distinctId,
                properties = properties,
            )
            return
        }

        val captureContext = PostHogRequestContext.resolveCaptureContext(distinctId, properties)
        super.captureExceptionStateless(
            exception,
            distinctId = captureContext.distinctId,
            properties = captureContext.properties,
        )
    }

    override fun captureException(
        exception: Throwable,
        distinctId: String?,
        options: PostHogCaptureOptions,
    ) {
        if (!enabled) {
            return
        }

        try {
            val captureContext = PostHogRequestContext.resolveCaptureContext(distinctId, options.properties)
            val callerProperties =
                mergeCaptureProperties(
                    distinctId = captureContext.distinctId,
                    properties = captureContext.properties,
                    userProperties = options.userProperties,
                    groups = options.groups,
                    appendFeatureFlags = options.appendFeatureFlags,
                    flags = options.flags,
                )

            val config = getConfig<com.posthog.PostHogConfig>()
            val exceptionProperties =
                throwableCoercer.fromThrowableToPostHogProperties(
                    exception,
                    inAppIncludes = config?.errorTrackingConfig?.inAppIncludes ?: listOf(),
                    releaseIdentifier = config?.releaseIdentifier,
                    inAppExcludes = config?.errorTrackingConfig?.inAppExcludes ?: listOf(),
                )
            // Caller properties merge AFTER the coerced exception properties (same order as core
            // captureExceptionStateless) so options can override reserved keys like $exception_level.
            callerProperties?.let { exceptionProperties.putAll(it) }

            // captureExceptionStateless cannot carry groups/user properties/timestamp, so the
            // options overload coerces above and goes through captureStateless directly.
            super.captureStateless(
                PostHogEventName.EXCEPTION.event,
                captureContext.distinctId,
                exceptionProperties,
                options.userProperties,
                options.userPropertiesSetOnce,
                options.groups,
                options.timestamp,
            )
        } catch (e: Throwable) {
            // error capture must never throw into user code (parity with captureExceptionStateless)
            getConfig<com.posthog.PostHogConfig>()?.logger?.log("captureException has thrown an exception: $e.")
        }
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
            // User-supplied `$feature/<key>` overrides generated value (matches Python behavior).
            props.putIfAbsent("\$feature/$key", flagValue)
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
        props.putIfAbsent("\$active_feature_flags", activeFlags)

        return props
    }

    override fun evaluateFlags(
        distinctId: String?,
        groups: Map<String, String>?,
        personProperties: Map<String, Any?>?,
        groupProperties: Map<String, Map<String, Any?>>?,
        flagKeys: List<String>?,
        onlyEvaluateLocally: Boolean,
        disableGeoip: Boolean,
    ): PostHogFeatureFlagEvaluations {
        val resolvedDistinctId = PostHogRequestContext.resolveDistinctId(distinctId)
        if (resolvedDistinctId.isNullOrBlank()) {
            return PostHogFeatureFlagEvaluations.empty(evaluationsHost)
        }

        val featureFlagsImpl =
            featureFlags as? PostHogFeatureFlags
                ?: return PostHogFeatureFlagEvaluations.empty(evaluationsHost)

        val result =
            featureFlagsImpl.evaluateFlags(
                distinctId = resolvedDistinctId,
                groups = groups,
                personProperties = personProperties,
                groupProperties = groupProperties,
                flagKeys = flagKeys,
                onlyEvaluateLocally = onlyEvaluateLocally,
                disableGeoip = disableGeoip,
            )

        return PostHogFeatureFlagEvaluations(
            distinctId = resolvedDistinctId,
            flagMap = result.flags,
            locallyEvaluated = result.locallyEvaluated,
            requestId = result.requestId,
            evaluatedAt = result.evaluatedAt,
            definitionsLoadedAt = result.definitionsLoadedAt,
            responseError = result.responseError,
            host = evaluationsHost,
            groups = groups,
        )
    }

    public companion object {
        /**
         * Sets up the SDK and returns an instance that you can hold and pass around.
         *
         * @param config Server SDK configuration.
         * @return The configured PostHog client instance.
         */
        @JvmStatic
        public fun <T : PostHogConfig> with(config: T): PostHogInterface {
            val instance = PostHog()
            instance.setup(config)
            return instance
        }
    }
}

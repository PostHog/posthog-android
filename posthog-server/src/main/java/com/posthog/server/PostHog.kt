package com.posthog.server

import com.posthog.FeatureFlagResult
import com.posthog.PostHogStateless
import com.posthog.errortracking.PostHogErrorTrackingAutoCaptureIntegration
import com.posthog.internal.FeatureFlag
import com.posthog.server.internal.EvaluationsHost
import com.posthog.server.internal.PostHogFeatureFlags
import com.posthog.server.internal.PostHogMemoryQueue

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
        // Hold setupLock across the whole lifecycle so the enabled-transition check and the handler
        // install stay atomic with the base's own setup. The monitor is reentrant, so super.setup
        // re-acquires it harmlessly. Without this, two concurrent setup() calls could both observe
        // alreadySetUp as false and the rejected one would install a handler bound to a config the
        // base discarded; and a concurrent close() could read uncaughtExceptionIntegration before it
        // was assigned and leak the process-wide handler after the client closed.
        synchronized(setupLock) {
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
                    // routing it through captureException preserves those via the shared coercer.
                    integration.installWith(
                        object : PostHogErrorTrackingAutoCaptureIntegration.CaptureTarget {
                            override fun capture(throwable: Throwable) {
                                captureException(throwable)
                            }

                            override fun flush() {
                                // Crash path only: capture() above enqueues on the queue executor, and the
                                // regular flush() runs inline on this (the crashing) thread — it would
                                // usually read the queue before that enqueue landed, send nothing, and let
                                // the event die with the JVM. flushBlocking orders the drain behind the
                                // pending enqueue and blocks the crashing thread for at most
                                // CRASH_FLUSH_TIMEOUT_MS, like the Rust SDK's bounded panic-hook flush.
                                // The server client always runs a PostHogMemoryQueue
                                // (PostHogConfig.asCoreConfig), so the fallback is unreachable in practice
                                // and only keeps the crash flush from silently becoming a no-op.
                                val memoryQueue = this@PostHog.queue as? PostHogMemoryQueue
                                if (memoryQueue != null) {
                                    memoryQueue.flushBlocking(CRASH_FLUSH_TIMEOUT_MS)
                                } else {
                                    this@PostHog.flush()
                                }
                            }
                        },
                    )
                    uncaughtExceptionIntegration = integration
                }
            }
        }
    }

    override fun close() {
        // Same lock as setup so the uninstall + field clear cannot race a concurrent setup() that is
        // still assigning uncaughtExceptionIntegration; super.close re-acquires the reentrant lock.
        synchronized(setupLock) {
            uncaughtExceptionIntegration?.uninstall()
            uncaughtExceptionIntegration = null
            super.close()
        }
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

            // captureExceptionStateless cannot carry groups/timestamp, so this overload takes the
            // shared core route instead: it runs the same enabled/opt-out and ignoredExceptionTypes
            // gates and merges the provided properties AFTER the coerced exception properties (so
            // options can still override reserved keys like $exception_level). The merge is passed
            // as a provider so `appendFeatureFlags` cannot fire a /flags request for an event the
            // gates then drop; options.userProperties feeds flag evaluation only — $exception
            // events do not perform person updates.
            super.captureExceptionEvent(
                exception,
                distinctId = captureContext.distinctId,
                groups = options.groups,
                timestamp = options.timestamp,
            ) {
                mergeCaptureProperties(
                    distinctId = captureContext.distinctId,
                    properties = captureContext.properties,
                    userProperties = options.userProperties,
                    groups = options.groups,
                    appendFeatureFlags = options.appendFeatureFlags,
                    flags = options.flags,
                )
            }
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
         * How long the crashing thread waits for the crash event's flush to reach the network attempt
         * before it delegates to the next handler and lets the JVM go down.
         */
        private const val CRASH_FLUSH_TIMEOUT_MS = 2_000L

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

package com.posthog

import com.posthog.internal.PostHogApi
import com.posthog.internal.PostHogApiEndpoint
import com.posthog.internal.PostHogFeatureFlags
import com.posthog.internal.PostHogMemoryPreferences
import com.posthog.internal.PostHogPreferences
import com.posthog.internal.PostHogPreferences.Companion.ALL_INTERNAL_KEYS
import com.posthog.internal.PostHogPreferences.Companion.ANONYMOUS_ID
import com.posthog.internal.PostHogPreferences.Companion.BUILD
import com.posthog.internal.PostHogPreferences.Companion.DISTINCT_ID
import com.posthog.internal.PostHogPreferences.Companion.GROUPS
import com.posthog.internal.PostHogPreferences.Companion.OPT_OUT
import com.posthog.internal.PostHogPreferences.Companion.VERSION
import com.posthog.internal.PostHogQueue
import com.posthog.internal.PostHogSendCachedEventsIntegration
import com.posthog.internal.PostHogSerializer
import com.posthog.internal.PostHogThreadFactory
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

public class PostHog private constructor(
    private val queueExecutor: ExecutorService =
        Executors.newSingleThreadScheduledExecutor(
            PostHogThreadFactory("PostHogQueueThread"),
        ),
    private val replayExecutor: ExecutorService =
        Executors.newSingleThreadScheduledExecutor(
            PostHogThreadFactory("PostHogReplayQueueThread"),
        ),
    private val featureFlagsExecutor: ExecutorService =
        Executors.newSingleThreadScheduledExecutor(
            PostHogThreadFactory("PostHogFeatureFlagsThread"),
        ),
    private val cachedEventsExecutor: ExecutorService =
        Executors.newSingleThreadScheduledExecutor(
            PostHogThreadFactory("PostHogSendCachedEventsThread"),
        ),
    private val reloadFeatureFlags: Boolean = true,
) : PostHogInterface {
    @Volatile
    private var enabled = false

    private val setupLock = Any()
    private val optOutLock = Any()
    private val anonymousLock = Any()
    private val sessionLock = Any()
    private val groupsLock = Any()

    // do not move to companion object, otherwise sessionId will be null
    private val sessionIdNone = UUID(0, 0)

    private var sessionId = sessionIdNone
    private val featureFlagsCalledLock = Any()

    private var config: PostHogConfig? = null

    private var featureFlags: PostHogFeatureFlags? = null
    private var queue: PostHogQueue? = null
    private var replayQueue: PostHogQueue? = null
    private var memoryPreferences = PostHogMemoryPreferences()
    private val featureFlagsCalled = mutableMapOf<String, MutableList<Any?>>()

    public override fun <T : PostHogConfig> setup(config: T) {
        synchronized(setupLock) {
            try {
                if (enabled) {
                    config.logger.log("Setup called despite already being setup!")
                    return
                }

                if (!apiKeys.add(config.apiKey)) {
                    config.logger.log("API Key: ${config.apiKey} already has a PostHog instance.")
                }

                val cachePreferences = config.cachePreferences ?: memoryPreferences
                config.cachePreferences = cachePreferences
                val api = PostHogApi(config)
                val queue = PostHogQueue(config, api, PostHogApiEndpoint.BATCH, config.storagePrefix, queueExecutor)
                val replayQueue = PostHogQueue(config, api, PostHogApiEndpoint.SNAPSHOT, config.replayStoragePrefix, replayExecutor)
                val featureFlags = PostHogFeatureFlags(config, api, featureFlagsExecutor)

                // no need to lock optOut here since the setup is locked already
                val optOut =
                    getPreferences().getValue(
                        OPT_OUT,
                        defaultValue = config.optOut,
                    ) as? Boolean
                optOut?.let {
                    config.optOut = optOut
                }

                this.config = config
                this.queue = queue
                this.replayQueue = replayQueue
                this.featureFlags = featureFlags

                if (config.isAndroid) {
                    val startDate = config.dateProvider.currentDate()
                    val sendCachedEventsIntegration =
                        PostHogSendCachedEventsIntegration(
                            config,
                            api,
                            startDate,
                            cachedEventsExecutor,
                        )

                    config.addIntegration(sendCachedEventsIntegration)
                }

                if (config.isAndroid) {
                    legacyPreferences(config, config.serializer)
                }

                enabled = true

                queue.start()

                if (config.isAndroid) {
                    startSession()
                }

                config.integrations.forEach {
                    try {
                        it.install()
                    } catch (e: Throwable) {
                        config.logger.log("Integration ${it.javaClass.name} failed to install: $e.")
                    }
                }

                // only because of testing in isolation, this flag is always enabled
                if (reloadFeatureFlags && config.preloadFeatureFlags) {
                    loadFeatureFlagsRequest(config.onFeatureFlags)
                }
            } catch (e: Throwable) {
                config.logger.log("Setup failed: $e.")
            }
        }
    }

    private fun getPreferences(): PostHogPreferences {
        return config?.cachePreferences ?: memoryPreferences
    }

    private fun legacyPreferences(
        config: PostHogConfig,
        serializer: PostHogSerializer,
    ) {
        val cachedPrefs = getPreferences().getValue(config.apiKey) as? String
        cachedPrefs?.let {
            try {
                serializer.deserialize<Map<String, Any>?>(it.reader())?.let { props ->
                    val anonymousId = props["anonymousId"] as? String
                    val distinctId = props["distinctId"] as? String

                    if (!anonymousId.isNullOrBlank()) {
                        this.anonymousId = anonymousId
                    }
                    if (!distinctId.isNullOrBlank()) {
                        this.distinctId = distinctId
                    }

                    getPreferences().remove(config.apiKey)
                }
            } catch (e: Throwable) {
                config.logger.log("Legacy cached prefs: $cachedPrefs failed to parse: $e.")
            }
        }
    }

    public override fun close() {
        synchronized(setupLock) {
            try {
                enabled = false

                config?.let { config ->
                    apiKeys.remove(config.apiKey)

                    config.integrations.forEach {
                        try {
                            it.uninstall()
                        } catch (e: Throwable) {
                            config.logger
                                .log("Integration ${it.javaClass.name} failed to uninstall: $e.")
                        }
                    }
                }

                queue?.stop()
                replayQueue?.stop()

                featureFlagsCalled.clear()

                endSession()
            } catch (e: Throwable) {
                config?.logger?.log("Close failed: $e.")
            }
        }
    }

    // TODO: python does not have anonymousId
    private var anonymousId: String
        get() {
            var anonymousId: String?
            synchronized(anonymousLock) {
                anonymousId = getPreferences().getValue(ANONYMOUS_ID) as? String
                if (anonymousId.isNullOrBlank()) {
                    anonymousId = UUID.randomUUID().toString()
                    this.anonymousId = anonymousId ?: ""
                }
            }
            return anonymousId ?: ""
        }
        set(value) {
            getPreferences().setValue(ANONYMOUS_ID, value)
        }

    private var distinctId: String
        get() {
            return getPreferences().getValue(
                DISTINCT_ID,
                defaultValue = anonymousId,
            ) as? String ?: ""
        }
        set(value) {
            getPreferences().setValue(DISTINCT_ID, value)
        }

    private fun getDynamicContext(): Map<String, Any> {
        val dynamicContext = mutableMapOf<String, Any>()
        dynamicContext["\$locale"] = "${Locale.getDefault().language}-${Locale.getDefault().country}"
        System.getProperty("http.agent")?.let {
            dynamicContext["\$user_agent"] = it
        }
        dynamicContext["\$timezone"] = TimeZone.getDefault().id
        return dynamicContext
    }

    private fun buildProperties(
        distinctId: String,
        properties: Map<String, Any>?,
        userProperties: Map<String, Any>?,
        userPropertiesSetOnce: Map<String, Any>?,
        groupProperties: Map<String, Any>?,
        appendSharedProps: Boolean = true,
        appendGroups: Boolean = true,
        isAndroid: Boolean = false,
    ): Map<String, Any> {
        val props = mutableMapOf<String, Any>()

        if (appendSharedProps) {
            val registeredPrefs = getPreferences().getAll()
            if (registeredPrefs.isNotEmpty()) {
                props.putAll(registeredPrefs)
            }

            config?.context?.getStaticContext()?.let {
                props.putAll(it)
            }

            // dynamic context
            props.putAll(getDynamicContext())
            config?.context?.getDynamicContext()?.let {
                props.putAll(it)
            }

            if (config?.sendFeatureFlagEvent == true) {
                featureFlags?.getFeatureFlags()?.let {
                    if (it.isNotEmpty()) {
                        val keys = mutableListOf<String>()
                        for (entry in it.entries) {
                            props["\$feature/${entry.key}"] = entry.value

                            // only add active feature flags
                            val active = entry.value as? Boolean ?: true

                            if (active) {
                                keys.add(entry.key)
                            }
                        }
                        props["\$active_feature_flags"] = keys
                    }
                }
            }
        }

        if (isAndroid) {
            synchronized(sessionLock) {
                if (sessionId != sessionIdNone) {
                    val sessionId = sessionId.toString()
                    props["\$session_id"] = sessionId
                    if (config?.sessionReplay == true) {
                        // Session replay requires $window_id, so we set as the same as $session_id.
                        // the backend might fallback to $session_id if $window_id is not present next.
                        props["\$window_id"] = sessionId
                    }
                }
            }
        }

        properties?.let {
            props.putAll(it)
        }

        userProperties?.let {
            props["\$set"] = it
        }

        userPropertiesSetOnce?.let {
            props["\$set_once"] = it
        }

        if (appendGroups) {
            // merge groups
            mergeGroups(groupProperties)?.let {
                props["\$groups"] = it
            }
        }

        if (isAndroid) {
            // Replay needs distinct_id also in the props
            // remove after https://github.com/PostHog/posthog/pull/18954 gets merged
            val propDistinctId = props["distinct_id"] as? String
            if (!appendSharedProps && config?.sessionReplay == true && propDistinctId.isNullOrBlank()) {
                // distinctId is already validated hence not empty or blank
                props["distinct_id"] = distinctId
            }
        }

        return props
    }

    private fun mergeGroups(groupProperties: Map<String, Any>?): Map<String, Any>? {
        val preferences = getPreferences()

        @Suppress("UNCHECKED_CAST")
        val groups = preferences.getValue(GROUPS) as? Map<String, Any>
        val newGroups = mutableMapOf<String, Any>()

        groups?.let {
            newGroups.putAll(it)
        }

        groupProperties?.let {
            newGroups.putAll(it)
        }

        return newGroups.ifEmpty { null }
    }

    public override fun capture(
        event: String,
        distinctId: String?,
        properties: Map<String, Any>?,
        userProperties: Map<String, Any>?,
        userPropertiesSetOnce: Map<String, Any>?,
        groupProperties: Map<String, Any>?,
    ) {
        try {
            if (!isEnabled()) {
                return
            }
            if (config?.optOut == true) {
                config?.logger?.log("PostHog is in OptOut state.")
                return
            }

            val newDistinctId = distinctId ?: this.distinctId

            if (newDistinctId.isBlank()) {
                config?.logger?.log("capture call not allowed, distinctId is invalid: $newDistinctId.")
                return
            }

            val isAndroid = config?.isAndroid == true

            var snapshotEvent = false
            if (isAndroid && event == "\$snapshot") {
                snapshotEvent = true
            }

            var groupIdentify = false
            if (event == GROUP_IDENTIFY) {
                groupIdentify = true
            }

            val mergedProperties =
                buildProperties(
                    newDistinctId,
                    properties = properties,
                    userProperties = userProperties,
                    userPropertiesSetOnce = userPropertiesSetOnce,
                    groupProperties = groupProperties,
                    // only append shared props if not a snapshot event
                    appendSharedProps = !snapshotEvent,
                    // only append groups if not a group identify event
                    appendGroups = !groupIdentify,
                    isAndroid = isAndroid,
                )

            // sanitize the properties or fallback to the original properties
            val sanitizedProperties = config?.propertiesSanitizer?.sanitize(mergedProperties.toMutableMap()) ?: mergedProperties

            val postHogEvent =
                PostHogEvent(
                    event,
                    newDistinctId,
                    properties = sanitizedProperties,
                )

            // Replay has its own queue
            if (snapshotEvent) {
                replayQueue?.add(postHogEvent)
                return
            }

            queue?.add(postHogEvent)
        } catch (e: Throwable) {
            config?.logger?.log("Capture failed: $e.")
        }
    }

    public override fun optIn() {
        if (!isEnabled()) {
            return
        }

        synchronized(optOutLock) {
            config?.optOut = false
            getPreferences().setValue(OPT_OUT, false)
        }
    }

    public override fun optOut() {
        if (!isEnabled()) {
            return
        }

        synchronized(optOutLock) {
            config?.optOut = true
            getPreferences().setValue(OPT_OUT, true)
        }
    }

    /**
     * Is Opt Out
     */
    public override fun isOptOut(): Boolean {
        if (!isEnabled()) {
            return true
        }
        return config?.optOut ?: true
    }

    public override fun screen(
        screenTitle: String,
        properties: Map<String, Any>?,
        distinctId: String?,
    ) {
        if (!isEnabled()) {
            return
        }

        val newDistinctId = distinctId ?: this.distinctId

        val props = mutableMapOf<String, Any>()
        props["\$screen_name"] = screenTitle

        properties?.let {
            props.putAll(it)
        }

        capture("\$screen", distinctId = newDistinctId, properties = props)
    }

    public override fun alias(
        alias: String,
        distinctId: String?,
    ) {
        if (!isEnabled()) {
            return
        }

        val newDistinctId = distinctId ?: this.distinctId

        val props = mutableMapOf<String, Any>()
        props["alias"] = alias

        capture("\$create_alias", distinctId = newDistinctId, properties = props)
    }

    public override fun identify(
        distinctId: String,
        userProperties: Map<String, Any>?,
        userPropertiesSetOnce: Map<String, Any>?,
    ) {
        if (!isEnabled()) {
            return
        }

        if (distinctId.isBlank()) {
            config?.logger?.log("identify call not allowed, distinctId is invalid: $distinctId.")
            return
        }

        val previousDistinctId = this.distinctId

        // TODO: anonymousId and previous distinct id are needed for java BE?
        val props = mutableMapOf<String, Any>()
        val anonymousId = this.anonymousId
        if (anonymousId.isNotBlank()) {
            props["\$anon_distinct_id"] = anonymousId
        } else {
            config?.logger?.log("identify called with invalid anonymousId: $anonymousId.")
        }

        capture(
            "\$identify",
            distinctId = distinctId,
            properties = props,
            userProperties = userProperties,
            userPropertiesSetOnce = userPropertiesSetOnce,
        )

        if (previousDistinctId != distinctId) {
            // We keep the AnonymousId to be used by decide calls and identify to link the previousId
            if (previousDistinctId.isNotBlank()) {
                this.anonymousId = previousDistinctId
            } else {
                config?.logger?.log("identify called with invalid former distinctId: $previousDistinctId.")
            }
            this.distinctId = distinctId

            // only because of testing in isolation, this flag is always enabled
            if (reloadFeatureFlags) {
                reloadFeatureFlags()
            }
        }
    }

    public override fun group(
        type: String,
        key: String,
        groupProperties: Map<String, Any>?,
        distinctId: String?,
    ) {
        if (!isEnabled()) {
            return
        }

        val newDistinctId = distinctId ?: this.distinctId

        val props = mutableMapOf<String, Any>()
        props["\$group_type"] = type
        props["\$group_key"] = key
        groupProperties?.let {
            props["\$group_set"] = it
        }

        val preferences = getPreferences()
        var reloadFeatureFlagsIfNewGroup = false

        synchronized(groupsLock) {
            @Suppress("UNCHECKED_CAST")
            val groups = preferences.getValue(GROUPS) as? Map<String, Any>
            val newGroups = mutableMapOf<String, Any>()

            groups?.let {
                val currentKey = it[type]

                if (key != currentKey) {
                    reloadFeatureFlagsIfNewGroup = true
                }

                newGroups.putAll(it)
            }
            newGroups[type] = key

            preferences.setValue(GROUPS, newGroups)
        }

        capture(GROUP_IDENTIFY, distinctId = newDistinctId, properties = props)

        // only because of testing in isolation, this flag is always enabled
        if (reloadFeatureFlags && reloadFeatureFlagsIfNewGroup) {
            loadFeatureFlagsRequest(null)
        }
    }

    public override fun reloadFeatureFlags(onFeatureFlags: PostHogOnFeatureFlags?) {
        if (!isEnabled()) {
            return
        }
        loadFeatureFlagsRequest(onFeatureFlags)
    }

    private fun loadFeatureFlagsRequest(onFeatureFlags: PostHogOnFeatureFlags?) {
        @Suppress("UNCHECKED_CAST")
        val groups = getPreferences().getValue(GROUPS) as? Map<String, Any>

        val distinctId = this.distinctId
        val anonymousId = this.anonymousId

        if (distinctId.isBlank()) {
            config?.logger?.log("Feature flags not loaded, distinctId is invalid: $distinctId")
            return
        }

        featureFlags?.loadFeatureFlags(distinctId, anonymousId = anonymousId, groups, onFeatureFlags)
    }

    public override fun isFeatureEnabled(
        key: String,
        defaultValue: Boolean,
        distinctId: String?,
    ): Boolean {
        if (!isEnabled()) {
            return defaultValue
        }
        return featureFlags?.isFeatureEnabled(key, defaultValue) ?: defaultValue
    }

    // TODO: only_evaluate_locally
    public override fun getFeatureFlag(
        key: String,
        defaultValue: Any?,
        distinctId: String?,
    ): Any? {
        if (!isEnabled()) {
            return defaultValue
        }
        val value = featureFlags?.getFeatureFlag(key, defaultValue) ?: defaultValue

        var shouldSendFeatureFlagEvent = true
        synchronized(featureFlagsCalledLock) {
            val values = featureFlagsCalled[key] ?: mutableListOf()
            if (values.contains(value)) {
                shouldSendFeatureFlagEvent = false
            } else {
                values.add(value)
                featureFlagsCalled[key] = values
            }
        }

        if (config?.sendFeatureFlagEvent == true && shouldSendFeatureFlagEvent) {
            val props = mutableMapOf<String, Any>()
            props["\$feature_flag"] = key
            // value should never be nullabe anyway
            props["\$feature_flag_response"] = value ?: ""

            capture("\$feature_flag_called", properties = props)
        }

        return value
    }

    public override fun getFeatureFlagPayload(
        key: String,
        defaultValue: Any?,
        distinctId: String?,
    ): Any? {
        if (!isEnabled()) {
            return defaultValue
        }
        return featureFlags?.getFeatureFlagPayload(key, defaultValue) ?: defaultValue
    }

    public override fun flush() {
        if (!isEnabled()) {
            return
        }
        queue?.flush()
        replayQueue?.flush()
    }

    public override fun reset() {
        if (!isEnabled()) {
            return
        }

        // only remove properties, preserve BUILD and VERSION keys in order to to fix over-sending
        // of 'Application Installed' events and under-sending of 'Application Updated' events
        val except = listOf(VERSION, BUILD)
        getPreferences().clear(except = except)
        featureFlags?.clear()
        queue?.clear()
        replayQueue?.clear()
        featureFlagsCalled.clear()
        endSession()
    }

    private fun isEnabled(): Boolean {
        if (!enabled) {
            config?.logger?.log("Setup isn't called.")
        }
        return enabled
    }

    public override fun register(
        key: String,
        value: Any,
    ) {
        if (!isEnabled()) {
            return
        }
        if (ALL_INTERNAL_KEYS.contains(key)) {
            config?.logger?.log("Key: $key is reserved for internal use.")
            return
        }
        getPreferences().setValue(key, value)
    }

    public override fun unregister(key: String) {
        if (!isEnabled()) {
            return
        }
        getPreferences().remove(key)
    }

    override fun distinctId(): String {
        if (!isEnabled()) {
            return ""
        }
        return distinctId
    }

    override fun debug(enable: Boolean) {
        if (!isEnabled()) {
            return
        }
        config?.debug = enable
    }

    override fun startSession() {
        synchronized(sessionLock) {
            if (sessionId == sessionIdNone) {
                sessionId = UUID.randomUUID()
            }
        }
    }

    override fun endSession() {
        synchronized(sessionLock) {
            sessionId = sessionIdNone
        }
    }

    override fun isSessionActive(): Boolean {
        var active: Boolean
        synchronized(sessionLock) {
            active = sessionId != sessionIdNone
        }
        return active
    }

    override fun <T : PostHogConfig> getConfig(): T? {
        @Suppress("UNCHECKED_CAST")
        return config as? T
    }

    public companion object : PostHogInterface {
        private var shared: PostHogInterface = PostHog()
        private var defaultSharedInstance = shared

        private const val GROUP_IDENTIFY = "\$groupidentify"

        private val apiKeys = mutableSetOf<String>()

        @PostHogVisibleForTesting
        public fun overrideSharedInstance(postHog: PostHogInterface) {
            shared = postHog
        }

        @PostHogVisibleForTesting
        public fun resetSharedInstance() {
            shared = defaultSharedInstance
        }

        /**
         * Setup the SDK and returns an instance that you can hold and pass it around
         * @param T the type of the Config
         * @property config the Config
         */
        public fun <T : PostHogConfig> with(config: T): PostHogInterface {
            val instance = PostHog()
            instance.setup(config)
            return instance
        }

        @PostHogVisibleForTesting
        internal fun <T : PostHogConfig> withInternal(
            config: T,
            queueExecutor: ExecutorService,
            replayExecutor: ExecutorService,
            featureFlagsExecutor: ExecutorService,
            cachedEventsExecutor: ExecutorService,
            reloadFeatureFlags: Boolean,
        ): PostHogInterface {
            val instance =
                PostHog(
                    queueExecutor,
                    replayExecutor,
                    featureFlagsExecutor,
                    cachedEventsExecutor,
                    reloadFeatureFlags = reloadFeatureFlags,
                )
            instance.setup(config)
            return instance
        }

        public override fun <T : PostHogConfig> setup(config: T) {
            shared.setup(config)
        }

        public override fun close() {
            shared.close()
        }

        public override fun capture(
            event: String,
            distinctId: String?,
            properties: Map<String, Any>?,
            userProperties: Map<String, Any>?,
            userPropertiesSetOnce: Map<String, Any>?,
            groupProperties: Map<String, Any>?,
        ) {
            shared.capture(
                event,
                distinctId = distinctId,
                properties = properties,
                userProperties = userProperties,
                userPropertiesSetOnce = userPropertiesSetOnce,
                groupProperties = groupProperties,
            )
        }

        public override fun identify(
            distinctId: String,
            userProperties: Map<String, Any>?,
            userPropertiesSetOnce: Map<String, Any>?,
        ) {
            shared.identify(
                distinctId,
                userProperties = userProperties,
                userPropertiesSetOnce = userPropertiesSetOnce,
            )
        }

        public override fun reloadFeatureFlags(onFeatureFlags: PostHogOnFeatureFlags?) {
            shared.reloadFeatureFlags(onFeatureFlags)
        }

        public override fun isFeatureEnabled(
            key: String,
            defaultValue: Boolean,
            distinctId: String?,
        ): Boolean = shared.isFeatureEnabled(key, defaultValue = defaultValue, distinctId = distinctId)

        public override fun getFeatureFlag(
            key: String,
            defaultValue: Any?,
            distinctId: String?,
        ): Any? = shared.getFeatureFlag(key, defaultValue = defaultValue, distinctId = distinctId)

        public override fun getFeatureFlagPayload(
            key: String,
            defaultValue: Any?,
            distinctId: String?,
        ): Any? = shared.getFeatureFlagPayload(key, defaultValue = defaultValue, distinctId = distinctId)

        public override fun flush() {
            shared.flush()
        }

        public override fun reset() {
            shared.reset()
        }

        public override fun optIn() {
            shared.optIn()
        }

        public override fun optOut() {
            shared.optOut()
        }

        public override fun group(
            type: String,
            key: String,
            groupProperties: Map<String, Any>?,
            distinctId: String?,
        ) {
            shared.group(type, key, groupProperties = groupProperties, distinctId = distinctId)
        }

        public override fun screen(
            screenTitle: String,
            properties: Map<String, Any>?,
            distinctId: String?,
        ) {
            shared.screen(screenTitle, properties = properties, distinctId = distinctId)
        }

        public override fun alias(
            alias: String,
            distinctId: String?,
        ) {
            shared.alias(alias, distinctId = distinctId)
        }

        public override fun isOptOut(): Boolean = shared.isOptOut()

        public override fun register(
            key: String,
            value: Any,
        ) {
            shared.register(key, value)
        }

        public override fun unregister(key: String) {
            shared.unregister(key)
        }

        override fun distinctId(): String = shared.distinctId()

        override fun debug(enable: Boolean) {
            shared.debug(enable)
        }

        override fun startSession() {
            shared.startSession()
        }

        override fun endSession() {
            shared.endSession()
        }

        override fun isSessionActive(): Boolean {
            return shared.isSessionActive()
        }

        override fun <T : PostHogConfig> getConfig(): T? {
            return shared.getConfig()
        }
    }
}

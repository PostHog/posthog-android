package com.posthog

import com.posthog.internal.PostHogApi
import com.posthog.internal.PostHogApiEndpoint
import com.posthog.internal.PostHogFeatureFlagCalledCache
import com.posthog.internal.PostHogFeatureFlagsInterface
import com.posthog.internal.PostHogMemoryPreferences
import com.posthog.internal.PostHogNoOpLogger
import com.posthog.internal.PostHogPreferences
import com.posthog.internal.PostHogPreferences.Companion.GROUPS
import com.posthog.internal.PostHogPreferences.Companion.OPT_OUT
import com.posthog.internal.PostHogPrintLogger
import com.posthog.internal.PostHogQueueInterface
import com.posthog.internal.PostHogThreadFactory
import com.posthog.internal.errortracking.ThrowableCoercer
import java.util.Date
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

public open class PostHogStateless protected constructor(
    private val queueExecutor: ExecutorService =
        Executors.newSingleThreadScheduledExecutor(
            PostHogThreadFactory("PostHogQueueThread"),
        ),
    private val featureFlagsExecutor: ExecutorService =
        Executors.newSingleThreadScheduledExecutor(
            PostHogThreadFactory("PostHogFeatureFlagsThread"),
        ),
) : PostHogStatelessInterface {
    @Volatile
    protected var enabled: Boolean = false

    protected val setupLock: Any = Any()
    protected val optOutLock: Any = Any()
    private var featureFlagsCalled: PostHogFeatureFlagCalledCache? = null

    @JvmField
    protected var config: PostHogConfig? = null

    protected var featureFlags: PostHogFeatureFlagsInterface? = null
    protected var queue: PostHogQueueInterface? = null
    protected var memoryPreferences: PostHogPreferences = PostHogMemoryPreferences()
    protected val throwableCoercer: ThrowableCoercer = ThrowableCoercer()

    public override fun <T : PostHogConfig> setup(config: T) {
        synchronized(setupLock) {
            try {
                if (enabled) {
                    config.logger.log("Setup called despite already being setup!")
                    return
                }
                config.logger =
                    if (config.logger is PostHogNoOpLogger) PostHogPrintLogger(config) else config.logger

                if (!apiKeys.add(config.apiKey)) {
                    config.logger.log("API Key: ${config.apiKey} already has a PostHog instance.")
                }

                config.cachePreferences = memoryPreferences
                val api = PostHogApi(config)
                val queue =
                    config.queueProvider(
                        config,
                        api,
                        PostHogApiEndpoint.BATCH,
                        config.storagePrefix,
                        queueExecutor,
                    )
                val remoteConfig = config.remoteConfigProvider(config, api, featureFlagsExecutor, null)

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
                this.featureFlags = remoteConfig
                this.featureFlagsCalled =
                    PostHogFeatureFlagCalledCache(config.featureFlagCalledCacheSize)

                enabled = true

                queue.start()
            } catch (e: Throwable) {
                config.logger.log("Setup failed: $e.")
            }
        }
    }

    protected fun getPreferences(): PostHogPreferences {
        return config?.cachePreferences ?: memoryPreferences
    }

    public override fun close() {
        synchronized(setupLock) {
            try {
                if (!isEnabled()) {
                    return
                }

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
                featureFlags?.shutDown()
            } catch (e: Throwable) {
                config?.logger?.log("Close failed: $e.")
            }
        }
    }

    private fun buildProperties(
        properties: Map<String, Any>?,
        userProperties: Map<String, Any>?,
        userPropertiesSetOnce: Map<String, Any>?,
        groups: Map<String, String>?,
        appendGroups: Boolean = true,
    ): Map<String, Any> {
        val props = mutableMapOf<String, Any>()

        val registeredPrefs = getPreferences().getAll()
        if (registeredPrefs.isNotEmpty()) {
            props.putAll(registeredPrefs)
        }

        config?.context?.getStaticContext()?.let {
            props.putAll(it)
        }

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

        userProperties?.let {
            props["\$set"] = it
        }

        userPropertiesSetOnce?.let {
            props["\$set_once"] = it
        }

        if (appendGroups) {
            // merge groups
            mergeGroups(groups)?.let {
                props["\$groups"] = it
            }
        }

        // Session replay should have the SDK info as well
        config?.context?.getSdkInfo()?.let {
            props.putAll(it)
        }

        properties?.let {
            props.putAll(it)
        }

        return props
    }

    protected fun mergeGroups(givenGroups: Map<String, String>?): Map<String, String>? {
        val preferences = getPreferences()

        @Suppress("UNCHECKED_CAST")
        val groups = preferences.getValue(GROUPS) as? Map<String, String>
        val newGroups = mutableMapOf<String, String>()

        groups?.let {
            newGroups.putAll(it)
        }

        givenGroups?.let {
            newGroups.putAll(it)
        }

        return newGroups.ifEmpty { null }
    }

    public override fun captureStateless(
        event: String,
        distinctId: String,
        properties: Map<String, Any>?,
        userProperties: Map<String, Any>?,
        userPropertiesSetOnce: Map<String, Any>?,
        groups: Map<String, String>?,
        timestamp: Date?,
    ) {
        try {
            if (!isEnabled()) {
                return
            }

            if (config?.optOut == true) {
                config?.logger?.log("PostHog is in OptOut state.")
                return
            }

            var groupIdentify = false
            if (event == GROUP_IDENTIFY) {
                groupIdentify = true
            }

            val mergedProperties =
                buildProperties(
                    properties = properties,
                    userProperties = userProperties,
                    userPropertiesSetOnce = userPropertiesSetOnce,
                    groups = groups,
                    appendGroups = !groupIdentify,
                )

            val postHogEvent =
                buildEvent(
                    event,
                    distinctId,
                    mergedProperties.toMutableMap(),
                    timestamp,
                )
            if (postHogEvent == null) {
                val originalMessage = "PostHog event $event was dropped"
                val message =
                    if (PostHogEventName.isUnsafeEditable(event)) {
                        "$originalMessage. This can cause unexpected behavior."
                    } else {
                        originalMessage
                    }
                config?.logger?.log(message)
                return
            }

            queue?.add(postHogEvent)
        } catch (e: Throwable) {
            config?.logger?.log("Capture failed: $e.")
        }
    }

    @Suppress("DEPRECATION")
    protected fun buildEvent(
        event: String,
        distinctId: String,
        properties: MutableMap<String, Any>,
        timestamp: Date? = null,
    ): PostHogEvent? {
        // sanitize the properties or fallback to the original properties
        val sanitizedProperties = config?.propertiesSanitizer?.sanitize(properties)?.toMutableMap() ?: properties
        val postHogEvent =
            PostHogEvent(
                event,
                distinctId,
                properties = sanitizedProperties,
                timestamp = timestamp ?: Date(),
            )
        var eventChecked: PostHogEvent? = postHogEvent

        val beforeSendList = config?.beforeSendList ?: emptyList()

        for (beforeSend in beforeSendList) {
            try {
                eventChecked = beforeSend.run(postHogEvent)
                if (eventChecked == null) {
                    config?.logger?.log("Event $event was rejected in beforeSend function")
                    return null
                }
            } catch (e: Throwable) {
                config?.logger?.log("Error in beforeSend function: $e")
                return null
            }
        }

        return eventChecked
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

    public override fun aliasStateless(
        distinctId: String,
        alias: String,
    ) {
        if (!isEnabled()) {
            return
        }

        val props = mutableMapOf<String, Any>()
        props["alias"] = alias

        captureStateless("\$create_alias", distinctId, properties = props)
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

        val props = mutableMapOf<String, Any>()

        captureStateless(
            "\$identify",
            distinctId = distinctId,
            properties = props,
            userProperties = userProperties,
            userPropertiesSetOnce = userPropertiesSetOnce,
        )
    }

    public override fun groupStateless(
        distinctId: String,
        type: String,
        key: String,
        groupProperties: Map<String, Any>?,
    ) {
        if (!isEnabled()) {
            return
        }

        val props = mutableMapOf<String, Any>()
        props["\$group_type"] = type
        props["\$group_key"] = key
        groupProperties?.let {
            props["\$group_set"] = it
        }

        captureStateless(GROUP_IDENTIFY, distinctId, properties = props)
    }

    public override fun isFeatureEnabledStateless(
        distinctId: String,
        key: String,
        defaultValue: Boolean,
        groups: Map<String, String>?,
        personProperties: Map<String, Any?>?,
        groupProperties: Map<String, Map<String, Any?>>?,
    ): Boolean {
        val value =
            getFeatureFlagStateless(
                distinctId,
                key,
                defaultValue,
                groups,
                personProperties,
                groupProperties,
            )

        if (value is Boolean) {
            return value
        }

        if (value is String) {
            return value.isNotEmpty()
        }

        return false
    }

    private fun sendFeatureFlagCalled(
        distinctId: String,
        key: String,
        value: Any?,
    ) {
        if (config?.sendFeatureFlagEvent == true) {
            val isNewlySeen = featureFlagsCalled?.add(distinctId, key, value) ?: false
            if (isNewlySeen) {
                val props = mutableMapOf<String, Any>()
                props["\$feature_flag"] = key
                // value should never be nullable anyway
                props["\$feature_flag_response"] = value ?: ""

                captureStateless("\$feature_flag_called", distinctId, properties = props)
            }
        }
    }

    public override fun getFeatureFlagStateless(
        distinctId: String,
        key: String,
        defaultValue: Any?,
        groups: Map<String, String>?,
        personProperties: Map<String, Any?>?,
        groupProperties: Map<String, Map<String, Any?>>?,
    ): Any? {
        if (!isEnabled()) {
            return defaultValue
        }
        val value =
            featureFlags?.getFeatureFlag(
                key,
                defaultValue,
                distinctId,
                groups,
                personProperties,
                groupProperties,
            ) ?: defaultValue

        sendFeatureFlagCalled(distinctId, key, value)

        return value
    }

    public override fun getFeatureFlagPayloadStateless(
        distinctId: String,
        key: String,
        defaultValue: Any?,
        groups: Map<String, String>?,
        personProperties: Map<String, Any?>?,
        groupProperties: Map<String, Map<String, Any?>>?,
    ): Any? {
        if (!isEnabled()) {
            return defaultValue
        }
        return featureFlags?.getFeatureFlagPayload(
            key,
            defaultValue,
            distinctId,
            groups,
            personProperties,
            groupProperties,
        ) ?: defaultValue
    }

    public override fun flush() {
        if (!isEnabled()) {
            return
        }
        queue?.flush()
    }

    protected fun isEnabled(): Boolean {
        if (!enabled) {
            config?.logger?.log("Setup isn't called.")
        }
        return enabled
    }

    override fun debug(enable: Boolean) {
        if (!isEnabled()) {
            return
        }
        config?.debug = enable
    }

    protected open fun <T : PostHogConfig> getConfig(): T? {
        @Suppress("UNCHECKED_CAST")
        return config as? T
    }

    override fun captureExceptionStateless(
        throwable: Throwable,
        distinctId: String?,
        properties: Map<String, Any>?,
    ) {
        if (!isEnabled()) {
            return
        }

        try {
            val exceptionProperties =
                throwableCoercer.fromThrowableToPostHogProperties(
                    throwable,
                    inAppIncludes = config?.errorTrackingConfig?.inAppIncludes ?: listOf(),
                )

            properties?.let {
                exceptionProperties.putAll(it)
            }

            var id = distinctId
            if (id.isNullOrBlank()) {
                exceptionProperties.set("\$process_person_profile", false)
                id = UUID.randomUUID().toString()
            }

            captureStateless(PostHogEventName.EXCEPTION.event, distinctId = id, properties = exceptionProperties)
        } catch (e: Throwable) {
            // we swallow all exceptions that the SDK has thrown by trying to convert
            // a captured exception to a PostHog exception event
            config?.logger?.log("captureException has thrown an exception: $e.")
        }
    }

    public companion object : PostHogStatelessInterface {
        private var shared: PostHogStatelessInterface = PostHogStateless()
        private var defaultSharedInstance = shared

        private const val GROUP_IDENTIFY = "\$groupidentify"

        private val apiKeys = mutableSetOf<String>()

        @PostHogVisibleForTesting
        public fun overrideSharedInstance(postHog: PostHogStatelessInterface) {
            shared = postHog
        }

        @PostHogVisibleForTesting
        public fun resetSharedInstance() {
            shared = defaultSharedInstance
        }

        /**
         * Set up the SDK and returns an instance that you can hold and pass it around
         * @param T the type of the Config
         * @property config the Config
         */
        public fun <T : PostHogConfig> with(config: T): PostHogStatelessInterface {
            val instance = PostHogStateless()
            instance.setup(config)
            return instance
        }

        public override fun <T : PostHogConfig> setup(config: T) {
            shared.setup(config)
        }

        public override fun close() {
            shared.close()
        }

        public override fun captureStateless(
            event: String,
            distinctId: String,
            properties: Map<String, Any>?,
            userProperties: Map<String, Any>?,
            userPropertiesSetOnce: Map<String, Any>?,
            groups: Map<String, String>?,
            timestamp: Date?,
        ) {
            shared.captureStateless(
                event,
                distinctId = distinctId,
                properties = properties,
                userProperties = userProperties,
                userPropertiesSetOnce = userPropertiesSetOnce,
                groups = groups,
                timestamp = timestamp,
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

        public override fun isFeatureEnabledStateless(
            distinctId: String,
            key: String,
            defaultValue: Boolean,
            groups: Map<String, String>?,
            personProperties: Map<String, Any?>?,
            groupProperties: Map<String, Map<String, Any?>>?,
        ): Boolean =
            shared.isFeatureEnabledStateless(
                distinctId,
                key,
                defaultValue,
                groups,
                personProperties,
                groupProperties,
            )

        public override fun getFeatureFlagStateless(
            distinctId: String,
            key: String,
            defaultValue: Any?,
            groups: Map<String, String>?,
            personProperties: Map<String, Any?>?,
            groupProperties: Map<String, Map<String, Any?>>?,
        ): Any? =
            shared.getFeatureFlagStateless(
                distinctId,
                key,
                defaultValue,
                groups,
                personProperties,
                groupProperties,
            )

        public override fun getFeatureFlagPayloadStateless(
            distinctId: String,
            key: String,
            defaultValue: Any?,
            groups: Map<String, String>?,
            personProperties: Map<String, Any?>?,
            groupProperties: Map<String, Map<String, Any?>>?,
        ): Any? =
            shared.getFeatureFlagPayloadStateless(
                distinctId,
                key,
                defaultValue,
                groups,
                personProperties,
                groupProperties,
            )

        public override fun flush() {
            shared.flush()
        }

        public override fun optIn() {
            shared.optIn()
        }

        public override fun optOut() {
            shared.optOut()
        }

        public override fun groupStateless(
            distinctId: String,
            type: String,
            key: String,
            groupProperties: Map<String, Any>?,
        ) {
            shared.groupStateless(distinctId, type, key, groupProperties = groupProperties)
        }

        public override fun aliasStateless(
            distinctId: String,
            alias: String,
        ) {
            shared.aliasStateless(distinctId, alias)
        }

        public override fun isOptOut(): Boolean = shared.isOptOut()

        override fun debug(enable: Boolean) {
            shared.debug(enable)
        }

        override fun captureExceptionStateless(
            throwable: Throwable,
            distinctId: String?,
            properties: Map<String, Any>?,
        ) {
            shared.captureExceptionStateless(throwable, distinctId, properties)
        }
    }
}

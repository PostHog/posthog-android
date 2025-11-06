package com.posthog

import com.posthog.errortracking.PostHogErrorTrackingAutoCaptureIntegration
import com.posthog.internal.PostHogApi
import com.posthog.internal.PostHogApiEndpoint
import com.posthog.internal.PostHogNoOpLogger
import com.posthog.internal.PostHogPreferences.Companion.ALL_INTERNAL_KEYS
import com.posthog.internal.PostHogPreferences.Companion.ANONYMOUS_ID
import com.posthog.internal.PostHogPreferences.Companion.BUILD
import com.posthog.internal.PostHogPreferences.Companion.DISTINCT_ID
import com.posthog.internal.PostHogPreferences.Companion.GROUPS
import com.posthog.internal.PostHogPreferences.Companion.IS_IDENTIFIED
import com.posthog.internal.PostHogPreferences.Companion.OPT_OUT
import com.posthog.internal.PostHogPreferences.Companion.PERSON_PROCESSING
import com.posthog.internal.PostHogPreferences.Companion.VERSION
import com.posthog.internal.PostHogPrintLogger
import com.posthog.internal.PostHogQueueInterface
import com.posthog.internal.PostHogRemoteConfig
import com.posthog.internal.PostHogSendCachedEventsIntegration
import com.posthog.internal.PostHogSerializer
import com.posthog.internal.PostHogSessionManager
import com.posthog.internal.PostHogThreadFactory
import com.posthog.internal.personPropertiesContext
import com.posthog.internal.replay.PostHogSessionReplayHandler
import com.posthog.internal.surveys.PostHogSurveysHandler
import com.posthog.vendor.uuid.TimeBasedEpochGenerator
import java.util.Date
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
    private val remoteConfigExecutor: ExecutorService =
        Executors.newSingleThreadScheduledExecutor(
            PostHogThreadFactory("PostHogRemoteConfigThread"),
        ),
    private val cachedEventsExecutor: ExecutorService =
        Executors.newSingleThreadScheduledExecutor(
            PostHogThreadFactory("PostHogSendCachedEventsThread"),
        ),
    private val reloadFeatureFlags: Boolean = true,
) : PostHogInterface, PostHogStateless() {
    private val anonymousLock = Any()
    private val identifiedLock = Any()
    private val groupsLock = Any()
    private val personProcessingLock: Any = Any()

    private val featureFlagsCalledLock = Any()

    private var remoteConfig: PostHogRemoteConfig? = null
    private var replayQueue: PostHogQueueInterface? = null
    private val featureFlagsCalled = mutableMapOf<String, MutableList<Any?>>()

    private var sessionReplayHandler: PostHogSessionReplayHandler? = null
    private var surveysHandler: PostHogSurveysHandler? = null

    private var isIdentifiedLoaded: Boolean = false
    private var isPersonProcessingLoaded: Boolean = false

    // this is called if the feature flags are loaded for the first time and recording isn't started yet
    private val internalOnFeatureFlagsLoaded =
        PostHogOnFeatureFlags {
            sessionReplayHandler?.let {
                if (isSessionReplayConfigEnabled()) {
                    // start will bail if session replay is already active anyway
                    startSessionReplay(resumeCurrent = true)
                }
            }
        }

    public override fun <T : PostHogConfig> setup(config: T) {
        synchronized(setupLock) {
            try {
                if (enabled) {
                    config.logger.log("Setup called despite already being setup!")
                    return
                }
                config.logger = if (config.logger is PostHogNoOpLogger) PostHogPrintLogger(config) else config.logger

                if (!apiKeys.add(config.apiKey)) {
                    config.logger.log("API Key: ${config.apiKey} already has a PostHog instance.")
                }

                val cachePreferences = config.cachePreferences ?: memoryPreferences
                config.cachePreferences = cachePreferences
                val api = PostHogApi(config)
                val queue = config.queueProvider(config, api, PostHogApiEndpoint.BATCH, config.storagePrefix, queueExecutor)
                val replayQueue = config.queueProvider(config, api, PostHogApiEndpoint.SNAPSHOT, config.replayStoragePrefix, replayExecutor)
                val featureFlags =
                    config.remoteConfigProvider(config, api, remoteConfigExecutor) {
                        getDefaultPersonProperties()
                    }

                // no need to lock optOut here since the setup is locked already
                val optOut =
                    getPreferences().getValue(
                        OPT_OUT,
                        defaultValue = config.optOut,
                    ) as? Boolean
                optOut?.let {
                    config.optOut = optOut
                }

                val startDate = config.dateProvider.currentDate()
                val sendCachedEventsIntegration =
                    PostHogSendCachedEventsIntegration(
                        config,
                        api,
                        startDate,
                        cachedEventsExecutor,
                    )

                this.config = config
                this.queue = queue
                this.replayQueue = replayQueue

                if (featureFlags is PostHogRemoteConfig) {
                    this.remoteConfig = featureFlags
                }

                // Notify surveys integration whenever remote config finishes loading
                remoteConfig?.onRemoteConfigLoaded = {
                    try {
                        val surveys = remoteConfig?.getSurveys() ?: emptyList()
                        surveysHandler?.onSurveysLoaded(surveys)
                    } catch (e: Throwable) {
                        config.logger.log("Failed to notify surveys loaded: $e.")
                    }
                }

                config.addIntegration(sendCachedEventsIntegration)
                config.addIntegration(PostHogErrorTrackingAutoCaptureIntegration(config))

                legacyPreferences(config, config.serializer)

                super.enabled = true

                queue.start()

                startSession()

                config.integrations.forEach {
                    try {
                        it.install(this)

                        if (it is PostHogSessionReplayHandler) {
                            sessionReplayHandler = it

                            // resume because we just created the session id above with
                            // the startSession call
                            if (isSessionReplayConfigEnabled()) {
                                startSessionReplay(resumeCurrent = true)
                            }
                        } else if (it is PostHogSurveysHandler) {
                            // surveys integration so we can notify it about captured events
                            surveysHandler = it
                            // Immediately push any cached surveys from remote config
                            try {
                                val surveys = remoteConfig?.getSurveys() ?: emptyList()
                                it.onSurveysLoaded(surveys)
                            } catch (e: Throwable) {
                                config.logger.log("Pushing cached surveys to integration failed: $e.")
                            }
                        }
                    } catch (e: Throwable) {
                        config.logger.log("Integration ${it.javaClass.name} failed to install: $e.")
                    }
                }

                // only because of testing in isolation, this flag is always enabled
                if (reloadFeatureFlags) {
                    when {
                        config.remoteConfig -> loadRemoteConfigRequest(internalOnFeatureFlagsLoaded, config.onFeatureFlags)
                        config.preloadFeatureFlags -> reloadFeatureFlags(config.onFeatureFlags)
                    }
                }
            } catch (e: Throwable) {
                config.logger.log("Setup failed: $e.")
            }
        }
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
                if (!isEnabled()) {
                    return
                }

                enabled = false

                config?.let { config ->
                    apiKeys.remove(config.apiKey)

                    config.integrations.forEach {
                        try {
                            it.uninstall()

                            if (it is PostHogSessionReplayHandler) {
                                sessionReplayHandler = null
                            } else if (it is PostHogSurveysHandler) {
                                surveysHandler = null
                            }
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

    private var anonymousId: String
        get() {
            var anonymousId: String?
            synchronized(anonymousLock) {
                anonymousId = getPreferences().getValue(ANONYMOUS_ID) as? String
                if (anonymousId.isNullOrBlank()) {
                    var uuid = TimeBasedEpochGenerator.generate()
                    // when getAnonymousId method is available, pass-through the value for modification
                    config?.getAnonymousId?.let { uuid = it(uuid) }
                    anonymousId = uuid.toString()
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

    private var isIdentified: Boolean = false
        get() {
            synchronized(identifiedLock) {
                if (!isIdentifiedLoaded) {
                    isIdentified = getPreferences().getValue(IS_IDENTIFIED) as? Boolean
                        ?: (distinctId != anonymousId)
                    isIdentifiedLoaded = true
                }
            }
            return field
        }
        set(value) {
            synchronized(identifiedLock) {
                field = value
                getPreferences().setValue(IS_IDENTIFIED, value)
            }
        }

    private fun buildProperties(
        distinctId: String,
        properties: Map<String, Any>?,
        userProperties: Map<String, Any>?,
        userPropertiesSetOnce: Map<String, Any>?,
        groups: Map<String, String>?,
        appendSharedProps: Boolean = true,
        appendGroups: Boolean = true,
    ): MutableMap<String, Any> {
        val props = mutableMapOf<String, Any>()

        if (appendSharedProps) {
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
                remoteConfig?.getFeatureFlags()?.let {
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

            props["\$is_identified"] = isIdentified
            props["\$process_person_profile"] = hasPersonProcessing()
        }

        // Session replay should have the SDK info as well
        config?.context?.getSdkInfo()?.let {
            props.putAll(it)
        }

        val isSessionReplayActive = isSessionReplayActive()

        PostHogSessionManager.getActiveSessionId()?.let { sessionId ->
            val tempSessionId = sessionId.toString()
            props["\$session_id"] = tempSessionId
            // only Session replay needs $window_id
            if (!appendSharedProps && isSessionReplayActive) {
                // Session replay requires $window_id, so we set as the same as $session_id.
                // the backend might fall back to $session_id if $window_id is not present next.
                props["\$window_id"] = tempSessionId
            }
        }

        properties?.let {
            props.putAll(it)
        }

        // only Session replay needs distinct_id also in the props
        // remove after https://github.com/PostHog/posthog/pull/18954 gets merged
        val propDistinctId = props["distinct_id"] as? String
        if (!appendSharedProps && isSessionReplayActive && propDistinctId.isNullOrBlank()) {
            // distinctId is already validated hence not empty or blank
            props["distinct_id"] = distinctId
        }

        return props
    }

    public override fun capture(
        event: String,
        distinctId: String?,
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

            val newDistinctId = distinctId ?: this.distinctId

            // if the user isn't identified but passed userProperties, userPropertiesSetOnce or groups,
            // we should still enable person processing since this is intentional
            if (userProperties?.isEmpty() == false || userPropertiesSetOnce?.isEmpty() == false || groups?.isEmpty() == false) {
                requirePersonProcessing("capture", ignoreMessage = true)
            }

            // Automatically set person properties for feature flags during capture event
            setPersonPropertiesForFlagsIfNeeded(userProperties, userPropertiesSetOnce)

            if (newDistinctId.isBlank()) {
                config?.logger?.log("capture call not allowed, distinctId is invalid: $newDistinctId.")
                return
            }

            var isSnapshotEvent = event == PostHogEventName.SNAPSHOT.event
            var groupIdentify = false
            if (event == PostHogEventName.GROUP_IDENTIFY.event) {
                groupIdentify = true
            }

            val mergedProperties =
                buildProperties(
                    newDistinctId,
                    properties = properties,
                    userProperties = userProperties,
                    userPropertiesSetOnce = userPropertiesSetOnce,
                    groups = groups,
                    // only append shared props if not a snapshot event
                    appendSharedProps = !isSnapshotEvent,
                    // only append groups if not a group identify event and not a snapshot
                    appendGroups = !groupIdentify,
                )

            val postHogEvent = buildEvent(event, newDistinctId, mergedProperties, timestamp)
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
            // Reevaluate if this is a snapshot event because the event might have been updated by the beforeSend hook
            isSnapshotEvent = postHogEvent.event == PostHogEventName.SNAPSHOT.event
            // if this is a $snapshot event and $session_id is missing, don't process then event
            if (isSnapshotEvent && postHogEvent.properties?.get("\$session_id") == null) {
                config?.logger?.log("${postHogEvent.event} event dropped, because the \$session_id property is missing")
                return
            }
            // Replay has its own queue
            if (isSnapshotEvent) {
                replayQueue?.add(postHogEvent)
            } else {
                super.captureStateless(
                    postHogEvent.event,
                    newDistinctId,
                    postHogEvent.properties ?: emptyMap(),
                    userProperties,
                    userPropertiesSetOnce,
                    groups,
                    timestamp,
                )
                // Notify surveys integration about the event
                surveysHandler?.onEvent(event)
            }
        } catch (e: Throwable) {
            config?.logger?.log("Capture failed: $e.")
        }
    }

    override fun captureException(
        throwable: Throwable,
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

            capture(PostHogEventName.EXCEPTION.event, properties = exceptionProperties)
        } catch (e: Throwable) {
            // we swallow all exceptions that the SDK has thrown by trying to convert
            // a captured exception to a PostHog exception event
            config?.logger?.log("captureException has thrown an exception: $e.")
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
    ) {
        if (!isEnabled()) {
            return
        }

        val props = mutableMapOf<String, Any>()
        props["\$screen_name"] = screenTitle

        properties?.let {
            props.putAll(it)
        }

        capture(PostHogEventName.SCREEN.event, properties = props)
    }

    public override fun alias(alias: String) {
        if (!isEnabled()) {
            return
        }

        if (!requirePersonProcessing("alias")) {
            return
        }

        val props = mutableMapOf<String, Any>()
        props["alias"] = alias

        capture(PostHogEventName.CREATE_ALIAS.event, properties = props)
    }

    /**
     * Returns fresh default device and app properties for feature flag evaluation.
     */
    private fun getDefaultPersonProperties(): Map<String, Any> {
        if (!isEnabled()) return emptyMap()
        if (config?.setDefaultPersonProperties != true) return emptyMap()

        return config?.context?.personPropertiesContext() ?: emptyMap()
    }

    private fun setPersonPropertiesForFlagsIfNeeded(
        userProperties: Map<String, Any>?,
        userPropertiesSetOnce: Map<String, Any>? = null,
    ) {
        if (!hasPersonProcessing()) return
        if (userProperties.isNullOrEmpty() && userPropertiesSetOnce.isNullOrEmpty()) return

        val allProperties = mutableMapOf<String, Any>()
        userPropertiesSetOnce?.let {
            allProperties.putAll(userPropertiesSetOnce)
        }
        userProperties?.let {
            // User properties override setOnce properties
            allProperties.putAll(userProperties)
        }

        remoteConfig?.setPersonPropertiesForFlags(allProperties)
    }

    private fun setGroupPropertiesForFlagsIfNeeded(
        type: String,
        groupProperties: Map<String, Any>?,
    ) {
        if (!hasPersonProcessing()) return
        if (groupProperties.isNullOrEmpty()) return

        remoteConfig?.setGroupPropertiesForFlags(type, groupProperties)
    }

    public override fun identify(
        distinctId: String,
        userProperties: Map<String, Any>?,
        userPropertiesSetOnce: Map<String, Any>?,
    ) {
        if (!isEnabled()) {
            return
        }

        if (!requirePersonProcessing("identify")) {
            return
        }

        if (distinctId.isBlank()) {
            config?.logger?.log("identify call not allowed, distinctId is invalid: $distinctId.")
            return
        }

        val previousDistinctId = this.distinctId

        val props = mutableMapOf<String, Any>()

        if (config?.reuseAnonymousId != true) {
            val anonymousId = this.anonymousId
            if (anonymousId.isNotBlank()) {
                props["\$anon_distinct_id"] = anonymousId
            } else {
                config?.logger?.log("identify called with invalid anonymousId: $anonymousId.")
            }
        }

        val hasDifferentDistinctId = previousDistinctId != distinctId
        if (hasDifferentDistinctId && !isIdentified) {
            // this has to be set before capture since this flag will be read during the event
            // capture
            synchronized(identifiedLock) {
                isIdentified = true
            }

            capture(
                PostHogEventName.IDENTIFY.event,
                distinctId = distinctId,
                properties = props,
                userProperties = userProperties,
                userPropertiesSetOnce = userPropertiesSetOnce,
            )

            if (config?.reuseAnonymousId != true) {
                // We keep the AnonymousId to be used by flags calls and identify to link the previousId
                if (previousDistinctId.isNotBlank()) {
                    this.anonymousId = previousDistinctId
                } else {
                    config?.logger?.log("identify called with invalid former distinctId: $previousDistinctId.")
                }
            }
            this.distinctId = distinctId

            // Automatically set person properties for feature flags during identify() call
            setPersonPropertiesForFlagsIfNeeded(userProperties, userPropertiesSetOnce)

            // only because of testing in isolation, this flag is always enabled
            if (reloadFeatureFlags) {
                reloadFeatureFlags(config?.onFeatureFlags)
            }
            // we need to make sure the user props update is for the same user
            // otherwise they have to reset and identify again
        } else if (!hasDifferentDistinctId && (userProperties?.isNotEmpty() == true || userPropertiesSetOnce?.isNotEmpty() == true)) {
            capture(
                "\$set",
                distinctId = distinctId,
                userProperties = userProperties,
                userPropertiesSetOnce = userPropertiesSetOnce,
            )
            // Note we don't reload flags on property changes as these get processed async
        } else {
            config?.logger?.log("already identified with id: $distinctId.")
        }
    }

    private fun hasPersonProcessing(): Boolean {
        return !(
            config?.personProfiles == PersonProfiles.NEVER ||
                (
                    config?.personProfiles == PersonProfiles.IDENTIFIED_ONLY &&
                        !isIdentified &&
                        !isPersonProcessingEnabled
                )
        )
    }

    private fun requirePersonProcessing(
        functionName: String,
        ignoreMessage: Boolean = false,
    ): Boolean {
        if (config?.personProfiles == PersonProfiles.NEVER) {
            if (!ignoreMessage) {
                config?.logger?.log("$functionName was called, but `personProfiles` is set to `never`. This call will be ignored.")
            }
            return false
        }
        isPersonProcessingEnabled = true
        return true
    }

    private var isPersonProcessingEnabled: Boolean = false
        get() {
            synchronized(personProcessingLock) {
                if (!isPersonProcessingLoaded) {
                    isPersonProcessingEnabled = getPreferences().getValue(PERSON_PROCESSING) as? Boolean
                        ?: false
                    isPersonProcessingLoaded = true
                }
            }
            return field
        }
        set(value) {
            synchronized(personProcessingLock) {
                // only set if it's different to avoid IO since this is called more often
                if (field != value) {
                    field = value
                    getPreferences().setValue(PERSON_PROCESSING, value)
                }
            }
        }

    public override fun group(
        type: String,
        key: String,
        groupProperties: Map<String, Any>?,
    ) {
        if (!isEnabled()) {
            return
        }

        if (!requirePersonProcessing("group")) {
            return
        }

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
            val groups = preferences.getValue(GROUPS) as? Map<String, String>
            val newGroups = mutableMapOf<String, String>()

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

        super.groupStateless(this.distinctId, type, key, groupProperties)

        // Automatically set group properties for feature flags
        setGroupPropertiesForFlagsIfNeeded(type, groupProperties)

        // only because of testing in isolation, this flag is always enabled
        if (reloadFeatureFlags && reloadFeatureFlagsIfNewGroup) {
            reloadFeatureFlags(config?.onFeatureFlags)
        }
    }

    public override fun reloadFeatureFlags(onFeatureFlags: PostHogOnFeatureFlags?) {
        if (!isEnabled()) {
            return
        }
        loadFeatureFlagsRequest(internalOnFeatureFlags = internalOnFeatureFlagsLoaded, onFeatureFlags = onFeatureFlags)
    }

    private fun loadFeatureFlagsRequest(
        internalOnFeatureFlags: PostHogOnFeatureFlags,
        onFeatureFlags: PostHogOnFeatureFlags? = null,
    ) {
        @Suppress("UNCHECKED_CAST")
        val groups = getPreferences().getValue(GROUPS) as? Map<String, String>

        val distinctId = this.distinctId
        var anonymousId: String? = null

        if (config?.reuseAnonymousId != true) {
            anonymousId = this.anonymousId
        }

        if (distinctId.isBlank()) {
            config?.logger?.log("Feature flags not loaded, distinctId is invalid: $distinctId")
            return
        }

        remoteConfig?.loadFeatureFlags(
            distinctId,
            anonymousId = anonymousId,
            groups,
            internalOnFeatureFlags = internalOnFeatureFlags,
            onFeatureFlags = onFeatureFlags,
        )
    }

    private fun loadRemoteConfigRequest(
        internalOnFeatureFlags: PostHogOnFeatureFlags?,
        onFeatureFlags: PostHogOnFeatureFlags?,
    ) {
        @Suppress("UNCHECKED_CAST")
        val groups = getPreferences().getValue(GROUPS) as? Map<String, String>

        val distinctId = this.distinctId
        var anonymousId: String? = null

        if (config?.reuseAnonymousId != true) {
            anonymousId = this.anonymousId
        }

        remoteConfig?.loadRemoteConfig(distinctId, anonymousId = anonymousId, groups, internalOnFeatureFlags, onFeatureFlags)
    }

    public override fun isFeatureEnabled(
        key: String,
        defaultValue: Boolean,
    ): Boolean {
        val value = getFeatureFlag(key, defaultValue)

        if (value is Boolean) {
            return value
        }

        if (value is String) {
            return value.isNotEmpty()
        }

        return false
    }

    private fun sendFeatureFlagCalled(
        key: String,
        value: Any?,
    ) {
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
            remoteConfig?.let {
                val flagDetails = it.getFlagDetails(key)
                val requestId = it.getRequestId()

                val props = mutableMapOf<String, Any>()
                props["\$feature_flag"] = key
                // value should never be nullabe anyway
                props["\$feature_flag_response"] = value ?: ""
                props["\$feature_flag_request_id"] = requestId ?: ""
                flagDetails?.let {
                    props["\$feature_flag_id"] = it.metadata.id
                    props["\$feature_flag_version"] = it.metadata.version
                    props["\$feature_flag_reason"] = it.reason?.description ?: ""
                }
                capture("\$feature_flag_called", properties = props)
            }
        }
    }

    public override fun getFeatureFlag(
        key: String,
        defaultValue: Any?,
    ): Any? {
        if (!isEnabled()) {
            return defaultValue
        }
        val value = remoteConfig?.getFeatureFlag(key, defaultValue) ?: defaultValue

        sendFeatureFlagCalled(key, value)

        return value
    }

    public override fun getFeatureFlagPayload(
        key: String,
        defaultValue: Any?,
    ): Any? {
        if (!isEnabled()) {
            return defaultValue
        }
        return remoteConfig?.getFeatureFlagPayload(key, defaultValue) ?: defaultValue
    }

    public override fun flush() {
        if (!isEnabled()) {
            return
        }
        super.flush()
        replayQueue?.flush()
    }

    public override fun setPersonPropertiesForFlags(
        userProperties: Map<String, Any>,
        reloadFeatureFlags: Boolean,
    ) {
        if (!isEnabled()) return
        if (!hasPersonProcessing()) return
        if (userProperties.isEmpty()) return

        remoteConfig?.setPersonPropertiesForFlags(userProperties)

        if (reloadFeatureFlags && this.reloadFeatureFlags) {
            this.reloadFeatureFlags()
        }
    }

    public override fun resetPersonPropertiesForFlags(reloadFeatureFlags: Boolean) {
        if (!isEnabled()) return
        if (!hasPersonProcessing()) return

        remoteConfig?.resetPersonPropertiesForFlags()

        if (reloadFeatureFlags && this.reloadFeatureFlags) {
            this.reloadFeatureFlags()
        }
    }

    public override fun setGroupPropertiesForFlags(
        type: String,
        groupProperties: Map<String, Any>,
        reloadFeatureFlags: Boolean,
    ) {
        if (!isEnabled()) return
        if (!hasPersonProcessing()) return

        if (groupProperties.isEmpty()) return

        remoteConfig?.setGroupPropertiesForFlags(type, groupProperties)

        if (reloadFeatureFlags && this.reloadFeatureFlags) {
            this.reloadFeatureFlags()
        }
    }

    public override fun resetGroupPropertiesForFlags(
        type: String?,
        reloadFeatureFlags: Boolean,
    ) {
        if (!isEnabled()) return
        if (!hasPersonProcessing()) return

        remoteConfig?.resetGroupPropertiesForFlags(type)

        if (reloadFeatureFlags && this.reloadFeatureFlags) {
            this.reloadFeatureFlags()
        }
    }

    public override fun reset() {
        if (!isEnabled()) {
            return
        }

        // only remove properties, preserve BUILD and VERSION keys in order to fix over-sending
        // of 'Application Installed' events and under-sending of 'Application Updated' events
        val except = mutableListOf(VERSION, BUILD)
        // preserve the ANONYMOUS_ID if reuseAnonymousId is enabled (for preserving a guest user
        // account on the device)
        if (config?.reuseAnonymousId == true) {
            except.add(ANONYMOUS_ID)
        }
        getPreferences().clear(except = except.toList())
        remoteConfig?.clear()
        featureFlagsCalled.clear()
        synchronized(identifiedLock) {
            isIdentifiedLoaded = false
        }
        synchronized(personProcessingLock) {
            isPersonProcessingLoaded = false
        }

        endSession()
        startSession()

        // reload flags as anon user
        // only because of testing in isolation, this flag is always enabled
        if (reloadFeatureFlags) {
            reloadFeatureFlags(config?.onFeatureFlags)
        }
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

    override fun startSession() {
        if (!isEnabled()) {
            return
        }

        PostHogSessionManager.startSession()
    }

    override fun endSession() {
        if (!isEnabled()) {
            return
        }

        PostHogSessionManager.endSession()
    }

    override fun isSessionActive(): Boolean {
        if (!isEnabled()) {
            return false
        }

        return PostHogSessionManager.isSessionActive()
    }

    override fun <T : PostHogConfig> getConfig(): T? {
        @Suppress("UNCHECKED_CAST")
        return super<PostHogStateless>.config as? T
    }

    private fun isSessionReplayConfigEnabled(): Boolean {
        return config?.sessionReplay == true
    }

    // this is used in cases where we know the session is already active
    // so we spare another locker
    private fun isSessionReplayFlagEnabled(): Boolean {
        return remoteConfig?.isSessionReplayFlagActive() == true
    }

    override fun isSessionReplayActive(): Boolean {
        if (!isEnabled()) {
            return false
        }

        return sessionReplayHandler?.isActive() == true && isSessionActive()
    }

    override fun startSessionReplay(resumeCurrent: Boolean) {
        if (!isEnabled()) {
            return
        }

        if (!isSessionReplayFlagEnabled()) {
            config?.logger?.log(
                "Could not start recording. Session replay is disabled, or remote config and feature flags are still being executed.",
            )
            return
        }

        sessionReplayHandler?.let {
            // already active
            if (it.isActive()) {
                return
            }

            if (resumeCurrent) {
                it.start(true)
            } else {
                endSession()
                startSession()
                it.start(false)
            }
        } ?: run {
            config?.logger?.log("Could not start recording. Session replay isn't installed.")
        }
    }

    override fun stopSessionReplay() {
        if (!isEnabled()) {
            return
        }

        sessionReplayHandler?.let {
            // already inactive
            if (!it.isActive()) {
                return
            }

            it.stop()
        } ?: run {
            config?.logger?.log("Session replay isn't installed.")
        }
    }

    override fun getSessionId(): UUID? {
        if (!isEnabled()) {
            return null
        }

        return PostHogSessionManager.getActiveSessionId()
    }

    public companion object : PostHogInterface {
        private var shared: PostHogInterface = PostHog()
        private var defaultSharedInstance = shared

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
         * Set up the SDK and returns an instance that you can hold and pass it around
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
            groups: Map<String, String>?,
            timestamp: Date?,
        ) {
            shared.capture(
                event,
                distinctId = distinctId,
                properties = properties,
                userProperties = userProperties,
                userPropertiesSetOnce = userPropertiesSetOnce,
                groups = groups,
                timestamp = timestamp,
            )
        }

        public override fun captureException(
            throwable: Throwable,
            properties: Map<String, Any>?,
        ) {
            shared.captureException(throwable, properties)
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
        ): Boolean = shared.isFeatureEnabled(key, defaultValue = defaultValue)

        public override fun getFeatureFlag(
            key: String,
            defaultValue: Any?,
        ): Any? = shared.getFeatureFlag(key, defaultValue = defaultValue)

        public override fun getFeatureFlagPayload(
            key: String,
            defaultValue: Any?,
        ): Any? = shared.getFeatureFlagPayload(key, defaultValue = defaultValue)

        public override fun flush() {
            shared.flush()
        }

        public override fun setPersonPropertiesForFlags(
            userProperties: Map<String, Any>,
            reloadFeatureFlags: Boolean,
        ) {
            shared.setPersonPropertiesForFlags(userProperties, reloadFeatureFlags)
        }

        public override fun resetPersonPropertiesForFlags(reloadFeatureFlags: Boolean) {
            shared.resetPersonPropertiesForFlags(reloadFeatureFlags)
        }

        public override fun setGroupPropertiesForFlags(
            type: String,
            groupProperties: Map<String, Any>,
            reloadFeatureFlags: Boolean,
        ) {
            shared.setGroupPropertiesForFlags(type, groupProperties, reloadFeatureFlags)
        }

        public override fun resetGroupPropertiesForFlags(
            type: String?,
            reloadFeatureFlags: Boolean,
        ) {
            shared.resetGroupPropertiesForFlags(type, reloadFeatureFlags)
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
        ) {
            shared.group(type, key, groupProperties = groupProperties)
        }

        public override fun screen(
            screenTitle: String,
            properties: Map<String, Any>?,
        ) {
            shared.screen(screenTitle, properties = properties)
        }

        public override fun alias(alias: String) {
            shared.alias(alias)
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

        override fun <T : PostHogConfig> getConfig(): T? {
            return shared.getConfig()
        }

        override fun isSessionActive(): Boolean {
            return shared.isSessionActive()
        }

        override fun isSessionReplayActive(): Boolean {
            return shared.isSessionReplayActive()
        }

        override fun startSessionReplay(resumeCurrent: Boolean) {
            shared.startSessionReplay(resumeCurrent)
        }

        override fun stopSessionReplay() {
            shared.stopSessionReplay()
        }

        override fun getSessionId(): UUID? {
            return shared.getSessionId()
        }
    }
}

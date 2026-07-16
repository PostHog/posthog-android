package com.posthog

import com.posthog.errortracking.PostHogErrorTrackingAutoCaptureIntegration
import com.posthog.internal.EndpointSpec
import com.posthog.internal.PostHogApi
import com.posthog.internal.PostHogApiEndpoint
import com.posthog.internal.PostHogDefaultPersonPropertiesProvider
import com.posthog.internal.PostHogFeatureFlagCalledProvider
import com.posthog.internal.PostHogNoOpLogger
import com.posthog.internal.PostHogOnRemoteConfigLoaded
import com.posthog.internal.PostHogPreferences.Companion.ALL_INTERNAL_KEYS
import com.posthog.internal.PostHogPreferences.Companion.ANONYMOUS_ID
import com.posthog.internal.PostHogPreferences.Companion.BUILD
import com.posthog.internal.PostHogPreferences.Companion.CAPTURE_PERFORMANCE
import com.posthog.internal.PostHogPreferences.Companion.DEVICE_ID
import com.posthog.internal.PostHogPreferences.Companion.DISTINCT_ID
import com.posthog.internal.PostHogPreferences.Companion.ERROR_TRACKING
import com.posthog.internal.PostHogPreferences.Companion.GROUPS
import com.posthog.internal.PostHogPreferences.Companion.IS_IDENTIFIED
import com.posthog.internal.PostHogPreferences.Companion.OPT_OUT
import com.posthog.internal.PostHogPreferences.Companion.PERSON_PROCESSING
import com.posthog.internal.PostHogPreferences.Companion.SESSION_REPLAY
import com.posthog.internal.PostHogPreferences.Companion.SURVEYS
import com.posthog.internal.PostHogPreferences.Companion.VERSION
import com.posthog.internal.PostHogPrintLogger
import com.posthog.internal.PostHogQueue
import com.posthog.internal.PostHogQueueInterface
import com.posthog.internal.PostHogRemoteConfig
import com.posthog.internal.PostHogSendCachedEventsIntegration
import com.posthog.internal.PostHogSerializer
import com.posthog.internal.PostHogSessionManager
import com.posthog.internal.PostHogThreadFactory
import com.posthog.internal.errortracking.PostHogExceptionStepsBuffer
import com.posthog.internal.personPropertiesContext
import com.posthog.internal.replay.PostHogSessionReplayHandler
import com.posthog.internal.sortMapRecursively
import com.posthog.internal.surveys.PostHogSurveysHandler
import com.posthog.logs.PostHogLogRecord
import com.posthog.logs.PostHogLogSeverity
import com.posthog.logs.PostHogLogger
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
    private val logsExecutor: ExecutorService =
        Executors.newSingleThreadScheduledExecutor(
            PostHogThreadFactory("PostHogLogsQueueThread"),
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
    private val deviceIdLock = Any()
    private val identifiedLock = Any()
    private val groupsLock = Any()
    private val personProcessingLock: Any = Any()

    private val featureFlagsCalledLock = Any()
    private val cachedPersonPropertiesLock = Any()

    private var replayQueue: PostHogQueueInterface<PostHogEvent>? = null

    private var logsQueue: PostHogQueueInterface<PostHogLogRecord>? = null

    /**
     * Captures application log records into PostHog's logs product
     * (separate from product analytics events). Use the severity-specific
     * helpers (`trace`/`debug`/`info`/`warn`/`error`/`fatal`) or the
     * generic [PostHogLogger.log].
     *
     * ```kotlin
     * PostHog.logger.info("checkout opened")
     * PostHog.logger.error("payment failed", mapOf("code" to "PAY_3001"))
     * ```
     *
     * Not to be confused with the internal `config.logger` debug sink.
     */
    public override val logger: PostHogLogger =
        PostHogLogger { message, severity, attributes ->
            captureLogInternal(message, severity, attributes, traceId = null, spanId = null, traceFlags = null)
        }

    @Volatile
    private var lastScreenName: String? = null

    // Logs rate-cap state. Tumbling window: when wall-clock time advances
    // past windowStartMillis + window, the counter resets to 1.
    private val logsRateCapLock = Any()
    private var logsRateCapWindowStartMillis: Long = 0
    private var logsRateCapWindowCount: Int = 0

    private val remoteConfig: PostHogRemoteConfig?
        get() = config?.remoteConfigHolder

    private val featureFlagsCalled = mutableMapOf<String, MutableList<Any?>>()

    // Used to deduplicate setPersonProperties calls
    private var cachedPersonPropertiesHash: String? = null

    private var sessionReplayHandler: PostHogSessionReplayHandler? = null
    private var surveysHandler: PostHogSurveysHandler? = null

    @Volatile
    private var exceptionStepsBuffer: PostHogExceptionStepsBuffer? = null

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
                config.logger =
                    if (config.logger is PostHogNoOpLogger) PostHogPrintLogger(config) else config.logger

                if (config.apiKey.isEmpty()) {
                    config.logger.log("PostHog SDK is disabled because the API key is required and was empty after trimming whitespace.")
                    return
                }

                if (!apiKeys.add(config.apiKey)) {
                    config.logger.log("API Key: ${config.apiKey} already has a PostHog instance.")
                }

                val cachePreferences = config.cachePreferences ?: memoryPreferences
                config.cachePreferences = cachePreferences
                val api = PostHogApi(config)
                val queue =
                    config.queueProvider(
                        config,
                        api,
                        PostHogApiEndpoint.BATCH,
                        config.storagePrefix,
                        queueExecutor,
                    )
                val replayQueue =
                    config.queueProvider(
                        config,
                        api,
                        PostHogApiEndpoint.SNAPSHOT,
                        config.replayStoragePrefix,
                        replayExecutor,
                    )
                // The events/snapshot queueProvider is intentionally kept
                // PostHogEvent-typed; logs have a different record type and
                // no `wrap me` extension point analogous to replay, so the
                // queue is constructed directly here rather than threading a
                // second generic through PostHogConfig.
                val logsQueue =
                    PostHogQueue(
                        config,
                        EndpointSpec.logs(config, api, config.logsStoragePrefix),
                        logsExecutor,
                    )
                val onRemoteConfigLoaded =
                    PostHogOnRemoteConfigLoaded {
                        // The callback fires whether the attempt succeeded or terminally failed;
                        // hasRemoteConfigFetched() tells them apart. On a failure no fresh values
                        // were applied, so integrations fall back to their cached state instead.
                        if (remoteConfig?.hasRemoteConfigFetched() == true) {
                            try {
                                val surveys = remoteConfig?.getSurveys() ?: emptyList()
                                surveysHandler?.onSurveysLoaded(surveys)
                            } catch (e: Throwable) {
                                config.logger.log("Failed to notify surveys loaded: $e.")
                            }

                            // Notify all integrations about remote config changes
                            notifyIntegrationsRemoteConfig(config, loaded = true)
                        } else {
                            notifyIntegrationsRemoteConfig(config, loaded = false)
                        }
                    }

                val featureFlags =
                    config.remoteConfigProvider(
                        config,
                        api,
                        remoteConfigExecutor,
                        PostHogDefaultPersonPropertiesProvider { getDefaultPersonProperties() },
                        onRemoteConfigLoaded,
                        PostHogFeatureFlagCalledProvider { key, value ->
                            sendFeatureFlagCalled(key = key, value = value)
                        },
                    )

                // The persisted OPT_OUT is resolved lazily by isOptedOut(): at this point
                // this.config is not assigned yet (so getPreferences() would read the in-memory
                // fallback), and in Direct Boot the store is not readable yet either. The latch is
                // per config, so a re-setup after close() re-resolves against the new config.
                optOutLoaded = false

                val sendCachedEventsIntegration =
                    PostHogSendCachedEventsIntegration(
                        config,
                        api,
                        cachedEventsExecutor,
                    )

                this.config = config
                this.queue = queue
                this.replayQueue = replayQueue
                this.logsQueue = logsQueue

                if (config.errorTrackingConfig.exceptionSteps.enabled) {
                    val maxBytes = config.errorTrackingConfig.exceptionSteps.maxBytes
                    if (maxBytes > 0) {
                        exceptionStepsBuffer =
                            PostHogExceptionStepsBuffer(
                                maxBytes = maxBytes,
                                serializer = config.serializer,
                                logger = config.logger,
                            )
                    } else {
                        config.logger.log("Exception steps disabled: maxBytes ($maxBytes) must be greater than 0.")
                    }
                }

                if (featureFlags is PostHogRemoteConfig) {
                    config.remoteConfigHolder = featureFlags
                }

                config.addIntegration(sendCachedEventsIntegration)
                config.addIntegration(PostHogErrorTrackingAutoCaptureIntegration(config))

                legacyPreferences(config, config.serializer)

                applyBootstrapIfNeeded(config)

                super.enabled = true

                // Initialize device_id if not already set. getDeviceId() handles lazy init
                // by seeding from the anonymous ID, providing a stable identifier for
                // device-level feature flag bucketing that survives identify() and reset().
                getDeviceId()

                queue.start()
                logsQueue.start()

                PostHogSessionManager.setOnSessionIdChangedListener {
                    try {
                        sessionReplayHandler?.onSessionIdChanged()
                    } catch (e: Throwable) {
                        config.logger.log("onSessionIdChanged listener failed: $e.")
                    }
                }

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
                @Suppress("DEPRECATION")
                if (reloadFeatureFlags) {
                    // Bootstrap already seeded the flags in RemoteConfig's init; fire the flags-loaded
                    // callbacks now so listeners aren't blocked on the first network response.
                    if (remoteConfig?.hasBootstrapFlags() == true) {
                        notifyFeatureFlagsCallback(internalOnFeatureFlagsLoaded)
                        notifyFeatureFlagsCallback(config.onFeatureFlags)
                    }
                    when {
                        config.remoteConfig ->
                            loadRemoteConfigRequest(
                                internalOnFeatureFlagsLoaded,
                                config.onFeatureFlags,
                            )

                        config.preloadFeatureFlags -> reloadFeatureFlags(config.onFeatureFlags)
                    }
                }

                // Reconcile a differing identified bootstrap after the setup reload is dispatched.
                // Kept here (not before integrations) so the identify()-triggered flags reload can't
                // resolve remote config ahead of setup's own /config load on the single-threaded
                // remote-config executor. The $identify merge links any setup-time events captured
                // under the prior anonymous id to the identified user server-side.
                reconcileBootstrapIdentityIfNeeded(config)
            } catch (e: Throwable) {
                config.logger.log("Setup failed: $e.")
            }
        }
    }

    private fun notifyIntegrationsRemoteConfig(
        config: PostHogConfig,
        loaded: Boolean,
    ) {
        config.integrations.forEach { integration ->
            try {
                integration.onRemoteConfig(loaded)
            } catch (e: Throwable) {
                config.logger.log("Integration ${integration.javaClass.name} onRemoteConfig failed: $e.")
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

    /**
     * Seeds the bootstrap distinct id on first launch only. Skipped once any identity is persisted
     * so a returning user is never reassigned.
     */
    private fun applyBootstrapIfNeeded(config: PostHogConfig) {
        val bootstrap = config.bootstrap ?: return
        val bootstrapId = bootstrap.distinctId
        if (bootstrapId.isNullOrBlank()) {
            return
        }

        // Self-guard like legacyPreferences: a failure here must only skip bootstrap seeding,
        // not abort the rest of setup() (queue start, integrations, flag reload).
        try {
            val preferences = getPreferences()
            // Persisted identity wins — never overwrite an existing anonymous id, and never
            // re-link traffic across a previous anon→identified merge.
            val persistedAnonymousId = preferences.getValue(ANONYMOUS_ID) as? String
            val persistedDistinctId = preferences.getValue(DISTINCT_ID) as? String
            val alreadyIdentified = preferences.getValue(IS_IDENTIFIED) as? Boolean == true

            if (!persistedAnonymousId.isNullOrBlank() ||
                !persistedDistinctId.isNullOrBlank() ||
                alreadyIdentified
            ) {
                return
            }

            if (bootstrap.isIdentifiedId) {
                // Already-identified user: seed the distinct id and mark identified, but leave the
                // anonymous id to generate on its own so device_id isn't derived from a real user id
                // (device_id survives reset() and would otherwise leak onto later users).
                this.distinctId = bootstrapId
                this.isIdentified = true
            } else {
                this.anonymousId = bootstrapId
            }
        } catch (e: Throwable) {
            config.logger.log("Applying bootstrap identity failed: $e.")
        }
    }

    /**
     * Reconciles a differing identified bootstrap against the local identity — fresh installs are
     * already seeded by [applyBootstrapIfNeeded]. Runs at the end of setup, after the initial flag
     * reload: [identify] needs the SDK enabled and captures an event.
     */
    private fun reconcileBootstrapIdentityIfNeeded(config: PostHogConfig) {
        val bootstrap = config.bootstrap ?: return
        if (!bootstrap.isIdentifiedId) {
            return
        }
        val bootstrapId = bootstrap.distinctId
        if (bootstrapId.isNullOrBlank() || !isEnabled()) {
            return
        }

        // Self-guard like applyBootstrapIfNeeded: the identity reads and identify() (which captures
        // an event) must not abort setup.
        try {
            if (distinctId == bootstrapId) {
                // Matching id with an identified bootstrap: upgrade an anonymous user to identified
                // without a redundant $identify or re-link (spec). A fresh-install seed is already
                // identified, so this only fires for a returning anonymous user with the same id.
                if (!isIdentified) {
                    this.isIdentified = true
                }
                return
            }
            if (isIdentified) {
                config.logger.log(
                    "Bootstrap distinctId differs from an already-identified user. The existing identity " +
                        "is preserved. Call reset() before reinitializing to switch users.",
                )
            } else {
                identify(bootstrapId)
            }
        } catch (e: Throwable) {
            config.logger.log("Reconciling bootstrap identity failed: $e.")
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
                logsQueue?.stop()

                featureFlagsCalled.clear()
                lastScreenName = null

                PostHogSessionManager.setOnSessionIdChangedListener(null)

                exceptionStepsBuffer?.clear()
                exceptionStepsBuffer = null

                endSession()
            } catch (e: Throwable) {
                config?.logger?.log("Close failed: $e.")
            }
        }
    }

    // An anonymousId generated while the preferences store was unavailable (see
    // PostHogPreferences.isAvailable): kept in memory only, so it cannot overwrite a persisted
    // id that was merely unreadable at the time. Guarded by anonymousLock.
    private var transientAnonymousId: String? = null

    @get:JvmName("getAnonymousIdInternal")
    private var anonymousId: String
        get() {
            var anonymousId: String?
            synchronized(anonymousLock) {
                // read availability before the value: if the store becomes readable in between,
                // this call stays on the transient path and the persisted id is never overwritten
                val preferencesAvailable = getPreferences().isAvailable()
                anonymousId = getPreferences().getValue(ANONYMOUS_ID) as? String
                if (anonymousId.isNullOrBlank()) {
                    anonymousId = transientAnonymousId ?: run {
                        var uuid = TimeBasedEpochGenerator.generate()
                        // when getAnonymousId method is available, pass-through the value for modification
                        config?.getAnonymousId?.let { uuid = it(uuid) }
                        uuid.toString()
                    }
                    if (preferencesAvailable) {
                        this.anonymousId = anonymousId ?: ""
                        transientAnonymousId = null
                    } else {
                        // an absent value is indistinguishable from an unreadable one, so persisting
                        // now could overwrite a returning user's id once the store unlocks
                        transientAnonymousId = anonymousId
                    }
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
                    // read availability before the value (see the anonymousId getter)
                    val preferencesAvailable = getPreferences().isAvailable()
                    val value =
                        getPreferences().getValue(IS_IDENTIFIED) as? Boolean
                            ?: (distinctId != anonymousId)
                    if (preferencesAvailable) {
                        isIdentified = value
                        isIdentifiedLoaded = true
                    } else {
                        // the fallback was computed against a transient identity; assigning through
                        // the setter would buffer it and clobber the persisted value on unlock, and
                        // caching it would hide the persisted value for the process lifetime
                        field = value
                    }
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
            stampCachedScreenName(props)
        }

        // Session replay should have the SDK info as well
        config?.context?.getSdkInfo()?.let {
            props.putAll(it)
        }

        val isSessionReplayActive = isSessionReplayActive()

        // Skip the getter when caller pre-attached an id: getActiveSessionId() can
        // silently rotate, and the caller's value wins via putAll either way.
        val propSessionId = properties?.get("\$session_id") as? String
        val sessionIdString =
            propSessionId?.takeIf { it.isNotBlank() }
                ?: PostHogSessionManager.getActiveSessionId()?.toString()

        sessionIdString?.let { tempSessionId ->
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

    /**
     * Stamps `$screen_name = lastScreenName` into [props]. Called inside
     * the `appendSharedProps` block in [buildProperties] BEFORE
     * `properties?.putAll`, so a caller-supplied `$screen_name` (incl.
     * posthog-flutter's passthrough, explicit empty for intentional
     * "unset") overwrites this stamp on merge.
     */
    private fun stampCachedScreenName(props: MutableMap<String, Any>) {
        val cached = lastScreenName
        if (!cached.isNullOrEmpty()) {
            props["\$screen_name"] = cached
        }
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
            if (isOptedOut()) {
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

            // Externally-built $exception events (e.g. the Flutter/RN bridge) carry only
            // serialized class names, so they are matched by name instead of isInstance.
            val ignored = config?.errorTrackingConfig?.ignoredExceptionTypes
            if (!ignored.isNullOrEmpty() &&
                event == PostHogEventName.EXCEPTION.event &&
                hasIgnoredTypeInExceptionList(properties, ignored)
            ) {
                config?.logger?.log(
                    "Skipping \$exception: an entry in \$exception_list matches ignoredExceptionTypes",
                )
                return
            }

            // Attach the buffered exception steps to any $exception event (unless the caller
            // already supplied them), so externally-built exceptions (e.g. from the Flutter/RN
            // bridge) carry steps too, not only those captured via captureException(throwable).
            val stepsBuffer = exceptionStepsBuffer
            val effectiveProperties =
                if (event == PostHogEventName.EXCEPTION.event && stepsBuffer != null) {
                    val mutableProperties = properties?.toMutableMap() ?: mutableMapOf()
                    stepsBuffer.attachTo(mutableProperties)
                    mutableProperties
                } else {
                    properties
                }

            val mergedProperties =
                buildProperties(
                    newDistinctId,
                    properties = effectiveProperties,
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
                surveysHandler?.onEvent(event, mergedProperties)
                // Notify session replay handler about the event for event triggers
                sessionReplayHandler?.onEvent(event, mergedProperties)
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
            if (isIgnoredThrowable(throwable)) {
                return
            }

            val exceptionProperties =
                throwableCoercer.fromThrowableToPostHogProperties(
                    throwable,
                    inAppIncludes = config?.errorTrackingConfig?.inAppIncludes ?: listOf(),
                    releaseIdentifier = config?.releaseIdentifier,
                )

            properties?.let {
                exceptionProperties.putAll(it)
            }

            // $exception_steps are attached in capture() for all $exception events,
            // so externally-built exceptions (e.g. the Flutter/RN bridge) get them too.
            capture(PostHogEventName.EXCEPTION.event, properties = exceptionProperties)
        } catch (e: Throwable) {
            // we swallow all exceptions that the SDK has thrown by trying to convert
            // a captured exception to a PostHog exception event
            config?.logger?.log("captureException has thrown an exception: $e.")
        }
    }

    private fun hasIgnoredTypeInExceptionList(
        properties: Map<String, Any>?,
        ignored: List<Class<out Throwable>>,
    ): Boolean {
        val exceptionList = properties?.get("\$exception_list") as? List<*> ?: return false
        val ignoredNames = ignored.map { it.name }
        return exceptionList.any { entry ->
            val exception = entry as? Map<*, *> ?: return@any false
            val type = exception["type"] as? String ?: return@any false
            val module = exception["module"] as? String
            // module + type rejoins the runtime class name ThrowableCoercer split apart
            val className = if (module.isNullOrEmpty()) type else "$module.$type"
            className in ignoredNames
        }
    }

    override fun addExceptionStep(
        message: String,
        properties: Map<String, Any>?,
    ) {
        try {
            if (!isEnabled()) {
                return
            }
            if (isOptedOut()) {
                return
            }
            val buffer = exceptionStepsBuffer ?: return
            // Record synchronously on the calling thread (no background dispatch): a step
            // recorded immediately before a crash must already be buffered when the
            // uncaught-exception handler captures it. The work is bounded and cheap.
            buffer.add(message, Date(), properties)
        } catch (e: Throwable) {
            // recording must never throw into the host app, even via a host-supplied logger
            safeLog("addExceptionStep has thrown an exception: $e.")
        }
    }

    public override fun captureLog(
        message: String,
        severity: PostHogLogSeverity,
        attributes: Map<String, Any>?,
        traceId: String?,
        spanId: String?,
        traceFlags: Int?,
    ) {
        captureLogInternal(message, severity, attributes, traceId, spanId, traceFlags)
    }

    /**
     * Shared implementation behind [captureLog] and the [logger] facade.
     * Builds a record from the call-site arguments + capture-time context
     * (distinctId, sessionId, screenName, app state, active feature flag
     * keys), runs the `beforeSend` chain, and enqueues to the logs queue.
     *
     * No-ops when the SDK is disabled or opted-out.
     */
    private fun captureLogInternal(
        message: String,
        severity: PostHogLogSeverity,
        attributes: Map<String, Any>?,
        traceId: String?,
        spanId: String?,
        traceFlags: Int?,
    ) {
        try {
            if (!isEnabled()) return
            val cfg = config ?: return
            if (isOptedOut()) return
            if (message.isBlank()) return

            // Filter + sort done inside PostHogRemoteConfig's existing lock
            // to avoid an unnecessary value-map copy on the hot capture path.
            val featureFlagKeys = remoteConfig?.getActiveFeatureFlagKeys() ?: emptyList()

            val record =
                PostHogLogRecord(
                    body = message,
                    level = severity,
                    // Defensive deep copy — caller may reuse the map (and any
                    // nested maps/lists inside it); the serializer reads it
                    // later on the logs executor thread.
                    attributes = attributes?.let { deepCopyAttributes(it) } ?: emptyMap(),
                    traceId = traceId,
                    spanId = spanId,
                    traceFlags = traceFlags,
                    distinctId = distinctId.takeIf { it.isNotBlank() },
                    sessionId = PostHogSessionManager.getActiveSessionId()?.toString(),
                    screenName = lastScreenName,
                    featureFlagKeys = featureFlagKeys,
                    appState = if (PostHogSessionManager.isAppInBackgroundSnapshot()) "background" else "foreground",
                    timeUnixNano = PostHogLogRecord.nanosNow(cfg.dateProvider),
                )

            val sendable =
                cfg.logs.runBeforeSend(record) { e ->
                    safeLog("Error in beforeSend function: ${e.javaClass.simpleName}")
                } ?: return

            // Rate cap fires after `beforeSend` so records dropped by the
            // caller don't consume the window budget — the cap reflects what
            // the SDK would actually send, not what the caller asked us to
            // try to send.
            if (!acquireLogsRateCap(cfg)) return

            logsQueue?.add(sendable)
        } catch (e: Throwable) {
            // Only the throwable class — a hook's exception message can embed
            // user log bodies / attributes (PII).
            safeLog("captureLog failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Recursive shallow-immutable copy of arbitrarily nested log attributes.
     * Maps, lists, sets, and arrays are duplicated so caller mutations after
     * `captureLog` returns can't race the serializer on the logs executor.
     * Leaves (strings, numbers, bools, opaque objects) are shared since they
     * are either immutable or treated as immutable by the wire format.
     */
    @Suppress("UNCHECKED_CAST")
    private fun deepCopyAttributes(map: Map<String, Any>): Map<String, Any> {
        val copy = LinkedHashMap<String, Any>(map.size)
        for ((k, v) in map) copy[k] = deepCopyValue(v)
        return copy
    }

    @Suppress("UNCHECKED_CAST")
    private fun deepCopyValue(value: Any): Any =
        when (value) {
            is Map<*, *> -> deepCopyAttributes(value as Map<String, Any>)
            is List<*> -> value.map { it?.let(::deepCopyValue) }
            is Set<*> -> value.map { it?.let(::deepCopyValue) }.toSet()
            is Array<*> -> value.map { it?.let(::deepCopyValue) }.toTypedArray()
            else -> value
        }

    /**
     * Invokes `config.logger.log` swallowing any throwable — guards against a
     * user-supplied logger that throws from inside a `catch` block (which
     * would otherwise escape `captureLog`/`captureLogs` and surface to the
     * caller). Guaranteed not to throw.
     */
    private fun safeLog(message: String) {
        try {
            config?.logger?.log(message)
        } catch (e: Throwable) {
            // The user-supplied logger threw. Fall back to the JVM's default
            // stack-trace stream (stderr on JVM, logcat on Android) so the
            // double-failure is at least visible if anyone goes looking.
            // `printStackTrace` itself can throw on a broken `Throwable` impl
            // or a closed stderr — the outer catch makes the contract above
            // ("guaranteed not to throw") actually hold.
            try {
                e.printStackTrace()
            } catch (_: Throwable) {
                // Nowhere left to write — give up silently rather than
                // crash the calling captureLog.
            }
        }
    }

    /**
     * Tumbling-window rate cap for log capture. Returns `true` if the call
     * is allowed, `false` if the window's budget is exhausted. Non-positive
     * `rateCapMaxLogs` or `rateCapWindowSeconds` disable the cap.
     */
    private fun acquireLogsRateCap(cfg: PostHogConfig): Boolean {
        synchronized(logsRateCapLock) {
            // Reads of the config knobs happen under the same lock that
            // protects the window state so a `(max, windowSeconds)` pair is
            // either entirely pre-update or entirely post-update, never a
            // torn mix if a caller mutates them concurrently.
            val max = cfg.logs.rateCapMaxLogs
            val windowSeconds = cfg.logs.rateCapWindowSeconds
            if (max <= 0 || windowSeconds <= 0) return true

            val now = cfg.dateProvider.currentTimeMillis()
            val windowMillis = windowSeconds * 1000L
            if (now - logsRateCapWindowStartMillis >= windowMillis) {
                logsRateCapWindowStartMillis = now
                logsRateCapWindowCount = 1
                return true
            }
            if (logsRateCapWindowCount >= max) return false
            logsRateCapWindowCount++
            return true
        }
    }

    // Whether the persisted OPT_OUT value has been resolved into config.optOut. Resolution is
    // deferred until the preferences store is readable (it is not in Direct Boot), gating on the
    // developer-supplied config.optOut default until then. Guarded by optOutLock.
    @Volatile
    private var optOutLoaded = false

    private fun isOptedOut(): Boolean {
        val config = this.config ?: return true
        if (!optOutLoaded) {
            synchronized(optOutLock) {
                if (!optOutLoaded && getPreferences().isAvailable()) {
                    (getPreferences().getValue(OPT_OUT, defaultValue = config.optOut) as? Boolean)?.let {
                        config.optOut = it
                    }
                    optOutLoaded = true
                }
            }
        }
        return config.optOut
    }

    public override fun optIn() {
        if (!isEnabled()) {
            return
        }

        synchronized(optOutLock) {
            config?.optOut = false
            getPreferences().setValue(OPT_OUT, false)
            // an explicit runtime choice; the deferred read must not override it
            optOutLoaded = true
        }
    }

    public override fun optOut() {
        if (!isEnabled()) {
            return
        }

        synchronized(optOutLock) {
            config?.optOut = true
            getPreferences().setValue(OPT_OUT, true)
            optOutLoaded = true
            exceptionStepsBuffer?.clear()
        }
    }

    /**
     * Is Opt Out
     */
    public override fun isOptOut(): Boolean {
        if (!isEnabled()) {
            return true
        }
        return isOptedOut()
    }

    /**
     * Records a screen view by capturing a `$screen` event with [screenTitle].
     *
     * The title is also cached and automatically attached as `$screen_name` to
     * every subsequent event (until [reset] or [close] clears it).
     *
     * To override the auto-attached value on a specific event, pass `$screen_name`
     * in that event's `properties` on the next [capture] call.
     *
     * @param screenTitle the screen name to record
     * @param properties additional properties to attach to this `$screen` event
     */
    public override fun screen(
        screenTitle: String,
        properties: Map<String, Any>?,
    ) {
        if (!isEnabled()) {
            return
        }

        val trimmedTitle = screenTitle.trim()
        if (trimmedTitle.isEmpty()) {
            return
        }

        // Cache for capture-time context snapshot on log records and for the
        // $screen_name auto-attach on subsequent events (see buildProperties).
        this.lastScreenName = trimmedTitle

        val props = mutableMapOf<String, Any>()
        props["\$screen_name"] = trimmedTitle

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

    public override fun captureFeatureView(
        flag: String,
        flagVariant: String?,
    ) {
        if (!isEnabled()) {
            return
        }
        val props = mutableMapOf<String, Any>()
        props["feature_flag"] = flag

        val variant = flagVariant ?: getFeatureFlag(flag, sendFeatureFlagEvent = false) ?: true
        if (variant is String) {
            props["feature_flag_variant"] = variant
        }

        val userProperties = mapOf("\$feature_view/$flag" to variant)

        capture(
            event = PostHogEventName.FEATURE_VIEW.event,
            properties = props,
            userProperties = userProperties,
        )
    }

    public override fun captureFeatureInteraction(
        flag: String,
        flagVariant: String?,
    ) {
        if (!isEnabled()) {
            return
        }
        val props = mutableMapOf<String, Any>()
        props["feature_flag"] = flag

        val variant = flagVariant ?: getFeatureFlag(flag, sendFeatureFlagEvent = false) ?: true
        if (variant is String) {
            props["feature_flag_variant"] = variant
        }

        val userProperties = mapOf("\$feature_interaction/$flag" to variant)

        capture(
            PostHogEventName.FEATURE_INTERACTION.event,
            properties = props,
            userProperties = userProperties,
        )
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
            if (shouldCapturePersonPropertiesEvent(
                    distinctId,
                    userProperties,
                    userPropertiesSetOnce,
                    "A duplicate identify call was made with the same properties. The \$set event has been ignored.",
                )
            ) {
                capture(
                    PostHogEventName.SET.event,
                    distinctId = distinctId,
                    userProperties = userProperties,
                    userPropertiesSetOnce = userPropertiesSetOnce,
                )
            }
            // Note we don't reload flags on property changes as these get processed async
        } else {
            config?.logger?.log("already identified with id: $distinctId.")
        }
    }

    public override fun setPersonProperties(
        userPropertiesToSet: Map<String, Any>?,
        userPropertiesToSetOnce: Map<String, Any>?,
    ) {
        if (!isEnabled()) {
            return
        }

        if (userPropertiesToSet.isNullOrEmpty() && userPropertiesToSetOnce.isNullOrEmpty()) {
            return
        }

        if (!requirePersonProcessing("setPersonProperties")) {
            return
        }

        val currentDistinctId = this.distinctId

        if (!shouldCapturePersonPropertiesEvent(
                currentDistinctId,
                userPropertiesToSet,
                userPropertiesToSetOnce,
                "A duplicate setPersonProperties call was made with the same properties. It has been ignored.",
            )
        ) {
            return
        }

        // Update person properties for flags (setOnce properties are applied first, then set properties override)
        val allProperties = mutableMapOf<String, Any>()
        userPropertiesToSetOnce?.let { allProperties.putAll(it) }
        userPropertiesToSet?.let { allProperties.putAll(it) }
        if (allProperties.isNotEmpty()) {
            setPersonPropertiesForFlags(allProperties, reloadFeatureFlags = false)
        }

        // Send the $set event
        capture(
            PostHogEventName.SET.event,
            distinctId = currentDistinctId,
            userProperties = userPropertiesToSet,
            userPropertiesSetOnce = userPropertiesToSetOnce,
        )
    }

    /**
     * Computes a hash for deduplicating setPersonProperties calls.
     * Similar to the JS SDK, this creates a JSON string representation of the properties.
     * Keys are sorted recursively to ensure deterministic hashing regardless of map insertion order.
     */
    private fun getPersonPropertiesHash(
        distinctId: String,
        userPropertiesToSet: Map<String, Any>?,
        userPropertiesToSetOnce: Map<String, Any>?,
    ): String {
        // Sort keys recursively to ensure deterministic hashing regardless of map insertion order
        val sortedSet = userPropertiesToSet?.let { sortMapRecursively(it) }
        val sortedSetOnce = userPropertiesToSetOnce?.let { sortMapRecursively(it) }

        // Create a consistent JSON-like string representation similar to JS SDK's jsonStringify
        val hashData =
            sortedMapOf(
                "distinct_id" to distinctId,
                "userPropertiesToSet" to sortedSet,
                "userPropertiesToSetOnce" to sortedSetOnce,
            )
        return config?.serializer?.serializeObject(hashData) ?: hashData.toString()
    }

    /**
     * Checks if person properties have changed by comparing hash values.
     * Updates the cached hash if different and returns true if the event should be captured.
     * Returns false if the hash matches (duplicate call), logging the provided message.
     */
    private fun shouldCapturePersonPropertiesEvent(
        distinctId: String,
        userPropertiesToSet: Map<String, Any>?,
        userPropertiesToSetOnce: Map<String, Any>?,
        duplicateLogMessage: String,
    ): Boolean {
        val hash = getPersonPropertiesHash(distinctId, userPropertiesToSet, userPropertiesToSetOnce)

        synchronized(cachedPersonPropertiesLock) {
            if (cachedPersonPropertiesHash == hash) {
                config?.logger?.log(duplicateLogMessage)
                return false
            }
            cachedPersonPropertiesHash = hash
        }
        return true
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
                    // read availability before the value (see the anonymousId getter)
                    val preferencesAvailable = getPreferences().isAvailable()
                    val value =
                        getPreferences().getValue(PERSON_PROCESSING) as? Boolean
                            ?: false
                    if (preferencesAvailable) {
                        isPersonProcessingEnabled = value
                        isPersonProcessingLoaded = true
                    } else {
                        // don't cache: the persisted value is unreadable, not absent, and must be
                        // re-resolved once the store unlocks (the setter's equality guard is what
                        // keeps this read-back from persisting)
                        field = value
                    }
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

        // gate here rather than relying on the stateless capture path, which reads the raw
        // config.optOut and would miss a persisted opt-out that isOptedOut() has not resolved yet
        if (isOptedOut()) {
            config?.logger?.log("PostHog is in OptOut state.")
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

    // Invokes the feature flags callback, swallowing exceptions like runOnFeatureFlagsCallbacks.
    private fun notifyFeatureFlagsCallback(onFeatureFlags: PostHogOnFeatureFlags?) {
        try {
            onFeatureFlags?.loaded()
        } catch (e: Throwable) {
            config?.logger?.log("Executing the feature flags callback failed: $e")
        }
    }

    public override fun reloadFeatureFlags(onFeatureFlags: PostHogOnFeatureFlags?) {
        if (!isEnabled()) {
            // Still invoke the callback so awaiting callers aren't left hanging.
            notifyFeatureFlagsCallback(onFeatureFlags)
            return
        }
        loadFeatureFlagsRequest(
            internalOnFeatureFlags = internalOnFeatureFlagsLoaded,
            onFeatureFlags = onFeatureFlags,
        )
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
            // Still invoke the callback so awaiting callers aren't left hanging.
            notifyFeatureFlagsCallback(onFeatureFlags)
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

        remoteConfig?.loadRemoteConfig(
            distinctId,
            anonymousId = anonymousId,
            groups,
            internalOnFeatureFlags,
            onFeatureFlags,
        )
    }

    public override fun isFeatureEnabled(
        key: String,
        defaultValue: Boolean,
        sendFeatureFlagEvent: Boolean?,
    ): Boolean {
        val value = getFeatureFlag(key, defaultValue, sendFeatureFlagEvent)

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
        sendFeatureFlagEvent: Boolean? = null,
    ) {
        if (remoteConfig == null) {
            return
        }
        val effectiveSendFeatureFlagEvent =
            sendFeatureFlagEvent
                ?: config?.sendFeatureFlagEvent
                ?: false

        if (effectiveSendFeatureFlagEvent) {
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

            if (shouldSendFeatureFlagEvent) {
                remoteConfig?.let {
                    val flagDetails = it.getFlagDetails(key)
                    val requestId = it.getRequestId()
                    val evaluatedAt = it.getEvaluatedAt()

                    val props = mutableMapOf<String, Any>()
                    props["\$feature_flag"] = key
                    // value should never be nullabe anyway
                    props["\$feature_flag_response"] = value ?: ""
                    requestId?.let { props["\$feature_flag_request_id"] = it }
                    evaluatedAt?.let { props["\$feature_flag_evaluated_at"] = it }
                    flagDetails?.let {
                        props["\$feature_flag_id"] = it.metadata.id
                        props["\$feature_flag_version"] = it.metadata.version
                        props["\$feature_flag_reason"] = it.reason?.description ?: ""
                        it.metadata.hasExperiment?.let { hasExperiment ->
                            props["\$feature_flag_has_experiment"] = hasExperiment
                        }
                    }
                    // Snapshot the three bootstrap fields under one lock so a concurrent reset()
                    // can't tear this event (a response present but its payload dropped, or a
                    // $used_bootstrap_value from a different instant). Null when the key had no
                    // bootstrap value, preserving the per-key gating of these properties.
                    it.getBootstrapCalledValues(key)?.let { bootstrap ->
                        props["\$feature_flag_bootstrapped_response"] = bootstrap.response
                        bootstrap.payload?.let { payload ->
                            props["\$feature_flag_bootstrapped_payload"] = payload
                        }
                        props["\$used_bootstrap_value"] = bootstrap.usedBootstrapValue
                    }
                    capture(PostHogEventName.FEATURE_FLAG_CALLED.event, properties = props)
                }
            }
        }
    }

    public override fun getFeatureFlag(
        key: String,
        defaultValue: Any?,
        sendFeatureFlagEvent: Boolean?,
    ): Any? {
        if (!isEnabled()) return defaultValue
        val flagValue = remoteConfig?.getFeatureFlagResult(key)?.value ?: defaultValue
        sendFeatureFlagCalled(key, flagValue, sendFeatureFlagEvent)
        return flagValue
    }

    public override fun getAllFeatureFlags(): List<FeatureFlagResult>? {
        if (!isEnabled()) return null
        val flags = remoteConfig?.getFeatureFlags()
        val results =
            flags?.mapNotNull { item ->
                val featureFlagResult = remoteConfig?.getFeatureFlagResult(item.key)
                featureFlagResult
            }
        return results
    }

    @Deprecated(
        message = "Use getFeatureFlagResult() instead; note it sends the \$feature_flag_called event by default.",
        replaceWith = ReplaceWith("getFeatureFlagResult(key)?.payload"),
    )
    public override fun getFeatureFlagPayload(
        key: String,
        defaultValue: Any?,
    ): Any? {
        if (!isEnabled()) return defaultValue
        return getFeatureFlagResult(key, sendFeatureFlagEvent = false)?.payload ?: defaultValue
    }

    public override fun getFeatureFlagResult(
        key: String,
        sendFeatureFlagEvent: Boolean?,
    ): FeatureFlagResult? {
        if (!isEnabled()) {
            return null
        }
        val result = remoteConfig?.getFeatureFlagResult(key)

        sendFeatureFlagCalled(key, result?.value, sendFeatureFlagEvent)

        return result
    }

    public override fun flush() {
        if (!isEnabled()) {
            return
        }
        super.flush()
        replayQueue?.flush()
        logsQueue?.flush()
    }

    public override fun setPersonPropertiesForFlags(
        userProperties: Map<String, Any>,
        reloadFeatureFlags: Boolean,
    ) {
        if (!isEnabled()) return
        if (userProperties.isEmpty()) return

        remoteConfig?.setPersonPropertiesForFlags(userProperties)

        if (reloadFeatureFlags && this.reloadFeatureFlags) {
            this.reloadFeatureFlags()
        }
    }

    public override fun resetPersonPropertiesForFlags(reloadFeatureFlags: Boolean) {
        if (!isEnabled()) return

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

        remoteConfig?.resetGroupPropertiesForFlags(type)

        if (reloadFeatureFlags && this.reloadFeatureFlags) {
            this.reloadFeatureFlags()
        }
    }

    public override fun reset() {
        if (!isEnabled()) {
            return
        }

        // Preserve BUILD and VERSION to prevent over-sending "Application Installed" events
        // and under-sending "Application Updated" events. Preserve DEVICE_ID to maintain
        // stable feature flag bucketing across identity changes.
        // Preserve SESSION_REPLAY, ERROR_TRACKING, CAPTURE_PERFORMANCE, and SURVEYS (project-level config
        // from /config, not user data) so each survives an identity change without an app restart.
        val except = mutableListOf(VERSION, BUILD, DEVICE_ID, SESSION_REPLAY, ERROR_TRACKING, CAPTURE_PERFORMANCE, SURVEYS)
        // preserve the ANONYMOUS_ID if reuseAnonymousId is enabled (for preserving a guest user
        // account on the device)
        if (config?.reuseAnonymousId == true) {
            except.add(ANONYMOUS_ID)
        }
        getPreferences().clear(except = except.toList())
        remoteConfig?.clear()
        featureFlagsCalled.clear()
        lastScreenName = null
        synchronized(cachedPersonPropertiesLock) {
            cachedPersonPropertiesHash = null
        }
        synchronized(identifiedLock) {
            isIdentifiedLoaded = false
        }
        synchronized(personProcessingLock) {
            isPersonProcessingLoaded = false
        }
        synchronized(anonymousLock) {
            transientAnonymousId = null
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

    override fun getAnonymousId(): String {
        if (!isEnabled()) {
            return ""
        }
        return anonymousId
    }

    override fun getDeviceId(): String {
        if (!isEnabled()) {
            return ""
        }
        synchronized(deviceIdLock) {
            // read availability before the value (see the anonymousId getter)
            val preferencesAvailable = getPreferences().isAvailable()
            val deviceId = getPreferences().getValue(DEVICE_ID) as? String
            if (deviceId.isNullOrBlank()) {
                // Lazy init for upgrades: existing installs won't have a device_id yet
                val anonId = anonymousId
                if (anonId.isNotBlank()) {
                    if (preferencesAvailable) {
                        getPreferences().setValue(DEVICE_ID, anonId)
                    }
                    return anonId
                }
                return ""
            }
            return deviceId
        }
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

    private fun shouldRecordSession(): Boolean {
        val sessionId = PostHogSessionManager.getActiveSessionId()?.toString()
        if (sessionId != null) {
            // sampling is deterministic so we can sample again for the same session id
            return remoteConfig?.makeSamplingDecision(sessionId) ?: true
        }
        // no session id
        return false
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
                if (!shouldRecordSession()) {
                    return
                }

                it.start(true)
            } else {
                endSession()
                startSession()

                if (!shouldRecordSession()) {
                    return
                }

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

        /**
         * Captures application log records into PostHog's logs product
         * (separate from product analytics events). Forwards to the shared
         * SDK instance.
         *
         * ```kotlin
         * PostHog.logger.info("checkout opened")
         * PostHog.logger.error("payment failed", mapOf("code" to "PAY_3001"))
         * ```
         */
        public override val logger: PostHogLogger
            get() = shared.logger

        public override fun captureLog(
            message: String,
            severity: PostHogLogSeverity,
            attributes: Map<String, Any>?,
            traceId: String?,
            spanId: String?,
            traceFlags: Int?,
        ) {
            shared.captureLog(message, severity, attributes, traceId, spanId, traceFlags)
        }

        @PostHogVisibleForTesting
        public fun overrideSharedInstance(postHog: PostHogInterface) {
            shared = postHog
        }

        @PostHogVisibleForTesting
        public fun resetSharedInstance() {
            shared = defaultSharedInstance
        }

        /**
         * Sets up the SDK and returns an instance that you can hold and pass around.
         *
         * @param config SDK configuration.
         * @return The configured PostHog client instance.
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
            logsExecutor: ExecutorService =
                Executors.newSingleThreadScheduledExecutor(
                    PostHogThreadFactory("PostHogLogsQueueThread"),
                ),
        ): PostHogInterface {
            val instance =
                PostHog(
                    queueExecutor = queueExecutor,
                    replayExecutor = replayExecutor,
                    logsExecutor = logsExecutor,
                    remoteConfigExecutor = featureFlagsExecutor,
                    cachedEventsExecutor = cachedEventsExecutor,
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

        public override fun addExceptionStep(
            message: String,
            properties: Map<String, Any>?,
        ) {
            shared.addExceptionStep(message, properties)
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
            sendFeatureFlagEvent: Boolean?,
        ): Boolean =
            shared.isFeatureEnabled(
                key,
                defaultValue = defaultValue,
                sendFeatureFlagEvent = sendFeatureFlagEvent,
            )

        public override fun getFeatureFlag(
            key: String,
            defaultValue: Any?,
            sendFeatureFlagEvent: Boolean?,
        ): Any? = shared.getFeatureFlag(key, defaultValue = defaultValue, sendFeatureFlagEvent)

        override fun getAllFeatureFlags(): List<FeatureFlagResult>? {
            return shared.getAllFeatureFlags()
        }

        @Deprecated(
            message = "Use getFeatureFlagResult() instead; note it sends the \$feature_flag_called event by default.",
            replaceWith = ReplaceWith("getFeatureFlagResult(key)?.payload"),
        )
        @Suppress("DEPRECATION")
        public override fun getFeatureFlagPayload(
            key: String,
            defaultValue: Any?,
        ): Any? = shared.getFeatureFlagPayload(key, defaultValue = defaultValue)

        public override fun getFeatureFlagResult(
            key: String,
            sendFeatureFlagEvent: Boolean?,
        ): FeatureFlagResult? = shared.getFeatureFlagResult(key, sendFeatureFlagEvent)

        public override fun flush() {
            shared.flush()
        }

        public override fun setPersonProperties(
            userPropertiesToSet: Map<String, Any>?,
            userPropertiesToSetOnce: Map<String, Any>?,
        ) {
            shared.setPersonProperties(userPropertiesToSet, userPropertiesToSetOnce)
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

        public override fun captureFeatureView(
            flag: String,
            flagVariant: String?,
        ) {
            shared.captureFeatureView(flag, flagVariant)
        }

        public override fun captureFeatureInteraction(
            flag: String,
            flagVariant: String?,
        ) {
            shared.captureFeatureInteraction(flag, flagVariant)
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

        override fun getAnonymousId(): String = shared.getAnonymousId()

        override fun getDeviceId(): String = shared.getDeviceId()

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

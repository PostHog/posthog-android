package com.posthog.internal

import com.posthog.PostHogInternal

/**
 * Interface for caching context either in memory or in the disk depending on the
 * concrete implementation
 */
@PostHogInternal
public interface PostHogPreferences {
    public fun getValue(
        key: String,
        defaultValue: Any? = null,
    ): Any?

    public fun setValue(
        key: String,
        value: Any,
    )

    public fun clear(except: List<String> = emptyList())

    public fun remove(key: String)

    public fun getAll(): Map<String, Any>

    public companion object {
        public const val GROUPS: String = "groups"
        public const val ANONYMOUS_ID: String = "anonymousId"
        public const val DISTINCT_ID: String = "distinctId"
        internal const val IS_IDENTIFIED = "isIdentified"
        internal const val PERSON_PROCESSING = "personProcessingEnabled"
        internal const val OPT_OUT = "opt-out"
        internal const val FLAGS = "flags"
        internal const val FEATURE_FLAGS = "featureFlags"
        internal const val FEATURE_FLAGS_PAYLOAD = "featureFlagsPayload"
        internal const val FEATURE_FLAG_REQUEST_ID = "feature_flag_request_id"
        internal const val SESSION_REPLAY = "sessionReplay"
        internal const val SURVEYS = "surveys"
        internal const val PERSON_PROPERTIES_FOR_FLAGS = "personPropertiesForFlags"
        internal const val GROUP_PROPERTIES_FOR_FLAGS = "groupPropertiesForFlags"
        public const val SURVEY_SEEN: String = "surveySeen"
        public const val VERSION: String = "version"
        public const val BUILD: String = "build"
        public const val STRINGIFIED_KEYS: String = "stringifiedKeys"

        public val ALL_INTERNAL_KEYS: Set<String> =
            setOf(
                GROUPS,
                ANONYMOUS_ID,
                DISTINCT_ID,
                IS_IDENTIFIED,
                PERSON_PROCESSING,
                OPT_OUT,
                FEATURE_FLAGS,
                FEATURE_FLAGS_PAYLOAD,
                SESSION_REPLAY,
                SURVEYS,
                SURVEY_SEEN,
                VERSION,
                BUILD,
                STRINGIFIED_KEYS,
                FEATURE_FLAG_REQUEST_ID,
                FLAGS,
                PERSON_PROPERTIES_FOR_FLAGS,
                GROUP_PROPERTIES_FOR_FLAGS,
            )
    }
}

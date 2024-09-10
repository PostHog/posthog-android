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
        internal const val ANONYMOUS_ID = "anonymousId"
        internal const val DISTINCT_ID = "distinctId"
        internal const val IS_IDENTIFIED = "isIdentified"
        internal const val OPT_OUT = "opt-out"
        internal const val FEATURE_FLAGS = "featureFlags"
        internal const val FEATURE_FLAGS_PAYLOAD = "featureFlagsPayload"
        public const val VERSION: String = "version"
        public const val BUILD: String = "build"
        public const val STRINGIFIED_KEYS: String = "stringifiedKeys"
        public const val ENABLE_PERSON_PROCESSING: String = "epp"

        public val ALL_INTERNAL_KEYS: Set<String> =
            setOf(
                GROUPS,
                ANONYMOUS_ID,
                DISTINCT_ID,
                IS_IDENTIFIED,
                OPT_OUT,
                FEATURE_FLAGS,
                FEATURE_FLAGS_PAYLOAD,
                VERSION,
                BUILD,
                STRINGIFIED_KEYS,
            )
    }
}

package com.posthog

/**
 * Controls when captured events should create or update PostHog person profiles.
 */
public enum class PersonProfiles {
    /** Never process person profile data. Anonymous users are not merged when they identify. */
    NEVER,

    /** Process person profile data for all captured events. */
    ALWAYS,

    /** Process person profile data only after `identify`, `alias`, or `group` calls. */
    IDENTIFIED_ONLY,
}

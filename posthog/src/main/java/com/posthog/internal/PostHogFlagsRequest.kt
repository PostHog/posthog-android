package com.posthog.internal

/**
 * The data structure for calling the flags API
 */
internal class PostHogFlagsRequest(
    apiKey: String,
    distinctId: String,
    anonymousId: String?,
    groups: Map<String, String>?,
    // add person_properties, group_properties
) : HashMap<String, Any>() {
    init {
        this["api_key"] = apiKey
        this["distinct_id"] = distinctId
        if (!anonymousId.isNullOrBlank()) {
            this["\$anon_distinct_id"] = anonymousId
        }
        if (groups?.isNotEmpty() == true) {
            this["\$groups"] = groups
        }
    }
}

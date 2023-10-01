package com.posthog.internal

internal class PostHogDecideRequest(
    apiKey: String,
    distinctId: String,
    anonymousId: String,
    groups: Map<String, Any>? = null,
    // add person_properties, group_properties
) : HashMap<String, Any>() {
    init {
        this["token"] = apiKey
        this["distinct_id"] = distinctId
        this["\$anon_distinct_id"] = anonymousId
        if (groups?.isNotEmpty() == true) {
            this["\$groups"] = groups
        }
    }
}

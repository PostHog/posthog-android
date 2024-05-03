package com.posthog.internal

/**
 * The decide data structure for calling the decide API
 */
internal class PostHogDecideRequest(
    apiKey: String,
    distinctId: String,
    anonymousId: String?,
    groups: Map<String, Any>?,
    disableGeoIP: Boolean?,
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
        if (disableGeoIP == true) {
            this["disable_geoip"] = disableGeoIP
        }
    }
}

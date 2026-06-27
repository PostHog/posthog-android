package com.posthog.internal

import java.util.TimeZone

/**
 * The data structure for calling the flags API
 */
internal class PostHogFlagsRequest(
    apiKey: String,
    distinctId: String,
    anonymousId: String? = null,
    deviceId: String? = null,
    groups: Map<String, String>? = null,
    personProperties: Map<String, Any?>? = null,
    groupProperties: Map<String, Map<String, Any?>>? = null,
    evaluationContexts: List<String>? = null,
    flagKeys: List<String>? = null,
    disableGeoip: Boolean = false,
) : HashMap<String, Any>() {
    init {
        this["api_key"] = apiKey
        this["distinct_id"] = distinctId
        this["timezone"] = TimeZone.getDefault().id
        if (!anonymousId.isNullOrBlank()) {
            this["\$anon_distinct_id"] = anonymousId
        }
        if (!deviceId.isNullOrBlank()) {
            this["\$device_id"] = deviceId
        }
        this["groups"] = groups ?: emptyMap<String, String>()
        if (personProperties?.isNotEmpty() == true) {
            this["person_properties"] =
                personProperties.toMutableMap().apply {
                    putIfAbsent("distinct_id", distinctId)
                }
        }
        this["group_properties"] = groupProperties ?: emptyMap<String, Map<String, Any?>>()
        if (evaluationContexts?.isNotEmpty() == true) {
            this["evaluation_contexts"] = evaluationContexts
        }
        if (flagKeys?.isNotEmpty() == true) {
            this["flag_keys_to_evaluate"] = flagKeys
        }
        this["geoip_disable"] = disableGeoip
    }
}

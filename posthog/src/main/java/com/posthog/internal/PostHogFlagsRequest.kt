package com.posthog.internal

import java.util.TimeZone

/**
 * The data structure for calling the flags API
 */
internal class PostHogFlagsRequest(
    projectToken: String,
    distinctId: String,
    anonymousId: String? = null,
    deviceId: String? = null,
    groups: Map<String, String>? = null,
    personProperties: Map<String, Any?>? = null,
    groupProperties: Map<String, Map<String, Any?>>? = null,
    evaluationContexts: List<String>? = null,
    flagKeys: List<String>? = null,
    disableGeoip: Boolean = false,
    personPropertyKeysKnownAbsent: Collection<String>? = null,
    groupPropertyKeysKnownAbsent: Map<String, Collection<String>>? = null,
) : HashMap<String, Any>() {
    init {
        this["api_key"] = projectToken
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
            this["person_properties"] = personProperties
        }
        this["group_properties"] = groupProperties ?: emptyMap<String, Map<String, Any?>>()
        if (evaluationContexts?.isNotEmpty() == true) {
            this["evaluation_contexts"] = evaluationContexts
        }
        if (flagKeys?.isNotEmpty() == true) {
            this["flag_keys_to_evaluate"] = flagKeys
        }
        this["geoip_disable"] = disableGeoip
        if (personPropertyKeysKnownAbsent?.isNotEmpty() == true) {
            this["person_property_keys_known_absent"] = personPropertyKeysKnownAbsent.toList()
        }
        val groupKeysKnownAbsent = groupPropertyKeysKnownAbsent?.filterValues { it.isNotEmpty() }
        if (groupKeysKnownAbsent?.isNotEmpty() == true) {
            this["group_property_keys_known_absent"] = groupKeysKnownAbsent.mapValues { it.value.toList() }
        }
    }
}

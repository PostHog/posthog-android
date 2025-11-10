package com.posthog.internal

/**
 * The data structure for calling the flags API
 */
internal class PostHogFlagsRequest(
    apiKey: String,
    distinctId: String,
    anonymousId: String? = null,
    groups: Map<String, String>? = null,
    personProperties: Map<String, Any?>? = null,
    groupProperties: Map<String, Map<String, Any?>>? = null,
    evaluationEnvironments: List<String>? = null,
) : HashMap<String, Any>() {
    init {
        this["api_key"] = apiKey
        this["distinct_id"] = distinctId
        if (!anonymousId.isNullOrBlank()) {
            this["\$anon_distinct_id"] = anonymousId
        }
        if (groups?.isNotEmpty() == true) {
            this["groups"] = groups
        }
        if (personProperties?.isNotEmpty() == true) {
            this["person_properties"] = personProperties
        }
        if (groupProperties?.isNotEmpty() == true) {
            this["group_properties"] = groupProperties
        }
        if (evaluationEnvironments?.isNotEmpty() == true) {
            this["evaluation_environments"] = evaluationEnvironments
        }
    }
}

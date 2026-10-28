package com.posthog.internal.replay

// Session recording v2 trigger groups (posthog-js V2TriggerGroupStrategy).
internal data class PostHogTriggerGroupsConfig(
    val groups: List<PostHogTriggerGroup>,
)

internal data class PostHogTriggerGroup(
    val id: String,
    val name: String,
    val sampleRate: Double?,
    val minDurationMs: Long?,
    val conditions: PostHogTriggerConditions,
)

internal enum class PostHogTriggerMatchType {
    ANY,
    ALL,
}

// A leg counts as configured when the raw config has it, even if none of its entries parse: like
// posthog-js, an unparseable condition then never matches instead of dropping out and letting the
// group record every session.
internal data class PostHogTriggerConditions(
    val matchType: PostHogTriggerMatchType,
    val events: List<PostHogTriggerEvent>,
    val urls: List<Regex>,
    val flag: PostHogTriggerFlag?,
    val properties: List<PostHogTriggerPropertyFilter>,
    val hasEventsLeg: Boolean = events.isNotEmpty(),
    val hasUrlsLeg: Boolean = urls.isNotEmpty(),
    val hasFlagLeg: Boolean = flag != null,
)

internal data class PostHogTriggerEvent(
    val name: String,
    val properties: List<PostHogTriggerPropertyFilter>,
)

internal data class PostHogTriggerFlag(
    val flag: String,
    val variant: String?,
)

internal data class PostHogTriggerPropertyFilter(
    val key: String,
    val type: String?,
    val operator: String?,
    val value: Any?,
)

// Null means v1: posthog-js keeps the v1 strategy unless version is 2 and a group parses.
internal fun parseTriggerGroupsConfig(
    sessionRecording: Map<String, Any?>,
    log: (String) -> Unit = {},
): PostHogTriggerGroupsConfig? {
    val version = (sessionRecording["version"] as? Number)?.toInt() ?: 1
    if (version != 2) return null

    val rawGroups = sessionRecording["triggerGroups"] as? List<*> ?: return null

    val groups =
        rawGroups.mapNotNull { raw ->
            val map = raw as? Map<*, *>
            val id = map?.get("id") as? String
            if (map == null || id.isNullOrBlank()) {
                log("Session recording trigger group without an id was ignored.")
                return@mapNotNull null
            }
            PostHogTriggerGroup(
                id = id,
                name = map["name"] as? String ?: "",
                sampleRate = parseTriggerGroupSampleRate(map["sampleRate"]),
                minDurationMs = (map["minDurationMs"] as? Number)?.toLong(),
                conditions = parseTriggerConditions(map["conditions"]),
            )
        }

    if (groups.isEmpty()) return null

    return PostHogTriggerGroupsConfig(groups)
}

// The API can send the rate as a string. One that doesn't parse samples out, as it does on web
// (NaN never passes the threshold); only a missing rate samples in.
private fun parseTriggerGroupSampleRate(raw: Any?): Double? =
    when (raw) {
        is Number -> raw.toDouble()
        is String -> raw.toDoubleOrNull() ?: 0.0
        else -> null
    }

private fun parseTriggerConditions(conditions: Any?): PostHogTriggerConditions {
    val map = conditions as? Map<*, *>
    val rawFlag = map?.get("flag")
    return PostHogTriggerConditions(
        matchType = if (map?.get("matchType") == "any") PostHogTriggerMatchType.ANY else PostHogTriggerMatchType.ALL,
        events = parseTriggerEvents(map?.get("events")),
        urls = parseTriggerUrls(map?.get("urls")),
        flag = parseTriggerFlag(rawFlag),
        properties = parseTriggerPropertyFilters(map?.get("properties")),
        hasEventsLeg = (map?.get("events") as? List<*>).orEmpty().isNotEmpty(),
        hasUrlsLeg = (map?.get("urls") as? List<*>).orEmpty().isNotEmpty(),
        hasFlagLeg = (rawFlag is String && rawFlag.isNotEmpty()) || rawFlag is Map<*, *>,
    )
}

private fun parseTriggerEvents(events: Any?): List<PostHogTriggerEvent> =
    (events as? List<*>).orEmpty().mapNotNull { raw ->
        val map = raw as? Map<*, *>
        val name = map?.get("name") as? String
        if (name == null) {
            null
        } else {
            PostHogTriggerEvent(
                name = name,
                properties = parseTriggerPropertyFilters(map["properties"]),
            )
        }
    }

private fun parseTriggerUrls(urls: Any?): List<Regex> =
    (urls as? List<*>).orEmpty().mapNotNull { raw ->
        val map = raw as? Map<*, *>
        val pattern = map?.get("url") as? String ?: return@mapNotNull null
        if (map["matching"] != "regex") return@mapNotNull null
        try {
            Regex(pattern)
        } catch (e: Throwable) {
            null
        }
    }

private fun parseTriggerFlag(flag: Any?): PostHogTriggerFlag? =
    when (flag) {
        is String -> PostHogTriggerFlag(flag, null)
        is Map<*, *> ->
            (flag["flag"] as? String)?.let { name ->
                PostHogTriggerFlag(name, flag["variant"] as? String)
            }
        else -> null
    }

internal fun parseTriggerPropertyFilters(filters: Any?): List<PostHogTriggerPropertyFilter> =
    (filters as? List<*>).orEmpty().mapNotNull { raw ->
        val map = raw as? Map<*, *>
        val key = map?.get("key") as? String
        if (key == null) {
            null
        } else {
            PostHogTriggerPropertyFilter(
                key = key,
                type = map["type"] as? String,
                operator = map["operator"] as? String,
                value = map["value"],
            )
        }
    }

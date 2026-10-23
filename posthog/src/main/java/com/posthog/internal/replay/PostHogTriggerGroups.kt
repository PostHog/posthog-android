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

internal data class PostHogTriggerConditions(
    val matchType: PostHogTriggerMatchType,
    val events: List<PostHogTriggerEvent>,
    val urls: List<Regex>,
    val flag: PostHogTriggerFlag?,
    val properties: List<PostHogTriggerPropertyFilter>,
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
                sampleRate = (map["sampleRate"] as? Number)?.toDouble(),
                minDurationMs = (map["minDurationMs"] as? Number)?.toLong(),
                conditions = parseTriggerConditions(map["conditions"]),
            )
        }

    if (groups.isEmpty()) return null

    return PostHogTriggerGroupsConfig(groups)
}

private fun parseTriggerConditions(conditions: Any?): PostHogTriggerConditions =
    PostHogTriggerConditions(
        matchType =
            if ((conditions as? Map<*, *>)?.get("matchType") == "any") {
                PostHogTriggerMatchType.ANY
            } else {
                PostHogTriggerMatchType.ALL
            },
        events = parseTriggerEvents((conditions as? Map<*, *>)?.get("events")),
        urls = parseTriggerUrls((conditions as? Map<*, *>)?.get("urls")),
        flag = parseTriggerFlag((conditions as? Map<*, *>)?.get("flag")),
        properties = parseTriggerPropertyFilters((conditions as? Map<*, *>)?.get("properties")),
    )

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

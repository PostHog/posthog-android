package com.posthog.internal.replay

import kotlin.math.abs

internal data class PostHogTriggerGroupMatch(
    val id: String,
    val name: String,
    val sampled: Boolean,
)

// Mirrors posthog-js triggerGroupsMatchSessionRecordingStatus.
internal data class PostHogTriggerGroupsDecision(
    val shouldRecord: Boolean,
    val hasPendingGroups: Boolean,
    val minDurationMs: Long?,
    val groupsCount: Int,
    val matchedGroups: List<PostHogTriggerGroupMatch>,
)

// Sampling decisions are kept in memory only: they depend on (sessionId, groupId, sampleRate)
// alone, so a restart re-derives the same decisions.
internal class PostHogTriggerGroupsEvaluator {
    private val lock = Any()

    private var groups: List<PostHogTriggerGroup> = emptyList()

    private var activationSessionId: String? = null
    private val eventActivatedGroupIds = mutableSetOf<String>()
    private val screenActivatedGroupIds = mutableSetOf<String>()

    private val samplingDecisions = mutableMapOf<String, StoredSamplingDecision>()

    private data class StoredSamplingDecision(
        val sessionId: String,
        val sampleRate: Double?,
        val sampled: Boolean,
    )

    fun onConfig(newGroups: List<PostHogTriggerGroup>) {
        synchronized(lock) {
            if (groups == newGroups) return
            samplingDecisions.keys.retainAll(newGroups.map { it.id }.toSet())
            groups = newGroups
        }
    }

    // Returns true when at least one group newly activated.
    fun onEvent(
        sessionId: String,
        eventName: String,
        eventProperties: Map<String, Any?>?,
        personProperties: Map<String, Any?>?,
    ): Boolean {
        synchronized(lock) {
            resetActivationForNewSessionLocked(sessionId)

            var anyNewlyActivated = false
            for (group in groups) {
                val conditions = group.conditions

                val eventMatched =
                    conditions.events.isNotEmpty() &&
                        group.id !in eventActivatedGroupIds &&
                        matchesEventLeg(conditions, eventName, eventProperties, personProperties)
                if (eventMatched) {
                    eventActivatedGroupIds.add(group.id)
                    anyNewlyActivated = true
                }

                if (eventName == SCREEN_EVENT_NAME) {
                    val screenName = eventProperties?.get(SCREEN_NAME_PROPERTY) as? String
                    // Screens stand in for the web url leg.
                    val screenMatched =
                        conditions.urls.isNotEmpty() &&
                            screenName != null &&
                            group.id !in screenActivatedGroupIds &&
                            conditions.urls.any { it.containsMatchIn(screenName) } &&
                            matchTriggerPropertyFilters(conditions.properties, eventProperties, personProperties)
                    if (screenMatched) {
                        screenActivatedGroupIds.add(group.id)
                        anyNewlyActivated = true
                    }
                }
            }
            return anyNewlyActivated
        }
    }

    // eventLegsReliable is false for React Native: its events are captured in JS and never
    // reach this evaluator, so event and screen legs would otherwise stay pending forever.
    fun evaluate(
        sessionId: String,
        flags: Map<String, Any?>?,
        personProperties: Map<String, Any?>?,
        eventLegsReliable: Boolean = true,
    ): PostHogTriggerGroupsDecision {
        synchronized(lock) {
            resetActivationForNewSessionLocked(sessionId)

            var shouldRecord = false
            var hasPendingGroups = false
            var minDurationMs: Long? = null
            val matchedGroups = mutableListOf<PostHogTriggerGroupMatch>()

            for (group in groups) {
                when (groupStatusLocked(group, flags, eventLegsReliable)) {
                    PostHogTriggerStatus.ACTIVATED -> {
                        val sampled = samplingDecisionLocked(group, sessionId)
                        matchedGroups.add(PostHogTriggerGroupMatch(group.id, group.name, sampled))
                        if (sampled) {
                            shouldRecord = true
                        }
                        val duration = group.minDurationMs
                        if (duration != null && (minDurationMs == null || duration < minDurationMs)) {
                            minDurationMs = duration
                        }
                    }
                    PostHogTriggerStatus.PENDING -> hasPendingGroups = true
                    PostHogTriggerStatus.DISABLED -> Unit
                }
            }

            return PostHogTriggerGroupsDecision(
                shouldRecord = shouldRecord,
                hasPendingGroups = hasPendingGroups,
                minDurationMs = minDurationMs,
                groupsCount = groups.size,
                matchedGroups = matchedGroups,
            )
        }
    }

    private enum class PostHogTriggerStatus {
        ACTIVATED,
        PENDING,
        DISABLED,
    }

    private fun groupStatusLocked(
        group: PostHogTriggerGroup,
        flags: Map<String, Any?>?,
        eventLegsReliable: Boolean,
    ): PostHogTriggerStatus {
        val conditions = group.conditions
        val hasEvents = conditions.events.isNotEmpty()
        val hasUrls = conditions.urls.isNotEmpty()
        val hasFlag = conditions.flag != null

        if (!hasEvents && !hasUrls && !hasFlag) return PostHogTriggerStatus.ACTIVATED

        val eventLeg =
            when {
                !hasEvents || !eventLegsReliable -> PostHogTriggerStatus.DISABLED
                group.id in eventActivatedGroupIds -> PostHogTriggerStatus.ACTIVATED
                else -> PostHogTriggerStatus.PENDING
            }
        val screenLeg =
            when {
                !hasUrls || !eventLegsReliable -> PostHogTriggerStatus.DISABLED
                group.id in screenActivatedGroupIds -> PostHogTriggerStatus.ACTIVATED
                else -> PostHogTriggerStatus.PENDING
            }
        val flagLeg = flagLegStatus(conditions.flag, flags)

        return if (conditions.matchType == PostHogTriggerMatchType.ANY) {
            orTriggerStatus(eventLeg, screenLeg, flagLeg)
        } else {
            andTriggerStatus(eventLeg, screenLeg, flagLeg)
        }
    }

    private fun flagLegStatus(
        flag: PostHogTriggerFlag?,
        flags: Map<String, Any?>?,
    ): PostHogTriggerStatus {
        if (flag == null) return PostHogTriggerStatus.DISABLED
        if (flags == null) return PostHogTriggerStatus.PENDING

        val value = flags[flag.flag]
        val matches =
            when (value) {
                is Boolean -> value
                is String ->
                    if (flag.variant != null) {
                        value == flag.variant
                    } else {
                        value.isNotEmpty()
                    }
                else -> false
            }
        return if (matches) PostHogTriggerStatus.ACTIVATED else PostHogTriggerStatus.PENDING
    }

    private fun orTriggerStatus(vararg statuses: PostHogTriggerStatus): PostHogTriggerStatus =
        when {
            PostHogTriggerStatus.ACTIVATED in statuses -> PostHogTriggerStatus.ACTIVATED
            PostHogTriggerStatus.PENDING in statuses -> PostHogTriggerStatus.PENDING
            else -> PostHogTriggerStatus.DISABLED
        }

    private fun andTriggerStatus(vararg statuses: PostHogTriggerStatus): PostHogTriggerStatus {
        val enabled = statuses.filter { it != PostHogTriggerStatus.DISABLED }.distinct()
        return when (enabled.size) {
            0 -> PostHogTriggerStatus.DISABLED
            1 -> enabled.first()
            else -> PostHogTriggerStatus.PENDING
        }
    }

    private fun matchesEventLeg(
        conditions: PostHogTriggerConditions,
        eventName: String,
        eventProperties: Map<String, Any?>?,
        personProperties: Map<String, Any?>?,
    ): Boolean {
        val namedEntries = conditions.events.filter { it.name == eventName }
        if (namedEntries.isEmpty()) return false

        val entryMatched =
            namedEntries.any { entry ->
                entry.properties.isEmpty() ||
                    matchTriggerPropertyFilters(entry.properties, eventProperties, personProperties)
            }
        if (!entryMatched) return false

        return matchTriggerPropertyFilters(conditions.properties, eventProperties, personProperties)
    }

    private fun samplingDecisionLocked(
        group: PostHogTriggerGroup,
        sessionId: String,
    ): Boolean {
        val stored = samplingDecisions[group.id]
        if (stored != null && stored.sessionId == sessionId && stored.sampleRate == group.sampleRate) {
            return stored.sampled
        }

        // A missing rate samples in, like the web clampToRange fallback.
        val sampled =
            group.sampleRate?.let { rate -> sampleOnTriggerProperty(sessionId + group.id, rate) } ?: true

        samplingDecisions[group.id] = StoredSamplingDecision(sessionId, group.sampleRate, sampled)
        return sampled
    }

    private fun resetActivationForNewSessionLocked(sessionId: String) {
        if (activationSessionId != sessionId) {
            activationSessionId = sessionId
            eventActivatedGroupIds.clear()
            screenActivatedGroupIds.clear()
        }
    }

    private companion object {
        const val SCREEN_EVENT_NAME: String = "\$screen"
        const val SCREEN_NAME_PROPERTY: String = "\$screen_name"
    }
}

// Same hash and threshold as posthog-js sampleOnProperty.
internal fun sampleOnTriggerProperty(
    input: String,
    percent: Double,
): Boolean = simpleTriggerHash(input) % 100 < (percent * 100).coerceIn(0.0, 100.0)

internal fun simpleTriggerHash(str: String): Long {
    var hash = 0
    for (char in str) {
        hash = hash * 31 + char.code
    }
    // JS Math.abs returns a double, so Int.MIN_VALUE maps to 2147483648 rather than to itself.
    return if (hash == Int.MIN_VALUE) 2147483648L else abs(hash.toLong())
}

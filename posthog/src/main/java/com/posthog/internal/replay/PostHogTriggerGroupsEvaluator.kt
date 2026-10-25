package com.posthog.internal.replay

import com.posthog.internal.sampleOnProperty

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

    // Read-only for activation: a caller holding a stale session id must not wipe the current
    // session's activated groups. Only onEvent moves activation to a new session.
    fun evaluate(
        sessionId: String,
        flags: Map<String, Any?>?,
    ): PostHogTriggerGroupsDecision {
        synchronized(lock) {
            val activatedSession = activationSessionId == sessionId

            var shouldRecord = false
            var hasPendingGroups = false
            var minDurationMs: Long? = null
            val matchedGroups = mutableListOf<PostHogTriggerGroupMatch>()

            for (group in groups) {
                when (groupStatusLocked(group, flags, activatedSession)) {
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
        activatedSession: Boolean,
    ): PostHogTriggerStatus {
        val conditions = group.conditions
        val hasEvents = conditions.hasEventsLeg
        val hasUrls = conditions.hasUrlsLeg
        val hasFlag = conditions.hasFlagLeg

        if (!hasEvents && !hasUrls && !hasFlag) return PostHogTriggerStatus.ACTIVATED

        val eventLeg =
            when {
                !hasEvents -> PostHogTriggerStatus.DISABLED
                activatedSession && group.id in eventActivatedGroupIds -> PostHogTriggerStatus.ACTIVATED
                else -> PostHogTriggerStatus.PENDING
            }
        val screenLeg =
            when {
                !hasUrls -> PostHogTriggerStatus.DISABLED
                activatedSession && group.id in screenActivatedGroupIds -> PostHogTriggerStatus.ACTIVATED
                else -> PostHogTriggerStatus.PENDING
            }
        val flagLeg = if (hasFlag) flagLegStatus(conditions.flag, flags) else PostHogTriggerStatus.DISABLED

        return if (conditions.matchType == PostHogTriggerMatchType.ANY) {
            orTriggerStatus(eventLeg, screenLeg, flagLeg)
        } else {
            andTriggerStatus(eventLeg, screenLeg, flagLeg)
        }
    }

    // A configured flag leg that didn't parse never matches.
    private fun flagLegStatus(
        flag: PostHogTriggerFlag?,
        flags: Map<String, Any?>?,
    ): PostHogTriggerStatus {
        if (flag == null || flags == null) return PostHogTriggerStatus.PENDING

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
            group.sampleRate?.let { rate -> sampleOnProperty(sessionId + group.id, rate) } ?: true

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

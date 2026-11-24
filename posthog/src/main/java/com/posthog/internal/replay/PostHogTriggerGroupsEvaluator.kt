package com.posthog.internal.replay

import com.posthog.internal.sampleOnProperty
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
import java.util.Objects

internal data class PostHogTriggerGroupMatch(
    val id: String,
    val name: String,
    val sampled: Boolean,
)

// Mirrors posthog-js triggerGroupsMatchSessionRecordingStatus.
@IgnoreJRERequirement
internal data class PostHogTriggerGroupsDecision(
    val shouldRecord: Boolean,
    val hasPendingGroups: Boolean,
    val minDurationMs: Long?,
    val groupsCount: Int,
    val matchedGroups: List<PostHogTriggerGroupMatch>,
    // `trigger group "<name>": <leg> condition not matched` for each pending leg of each pending group.
    val pendingConditions: List<String> = emptyList(),
)

// Sampling decisions are kept in memory only: they depend on (sessionId, groupId, sampleRate)
// alone, so a restart re-derives the same decisions.
internal class PostHogTriggerGroupsEvaluator {
    private val lock = Any()

    private var groups: List<PostHogTriggerGroup> = emptyList()

    private var activationSessionId: String? = null

    // Sessions that activation moved away from. A call for one of them is late and must not take activation back.
    private val leftSessionIds = LinkedHashSet<String>()
    private val eventActivatedGroupIds = mutableSetOf<String>()
    private val screenActivatedGroupIds = mutableSetOf<String>()
    private var lastScreenName: String? = null
    private var lastScreenSessionId: String? = null
    private var screenCheckedSessionId: String? = null

    // The last seen screen was remembered, and the current groups were not checked against it yet.
    private var lastScreenUnevaluated = false

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

    // A group load can change the result for the screen the app is on (other groups, other person
    // properties), so the next check with includeUnevaluatedScreen takes that screen again.
    fun onGroupsLoaded() {
        synchronized(lock) {
            lastScreenUnevaluated = true
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
            val screenName = screenNameOf(eventName, eventProperties)
            val late = !moveActivationToSessionLocked(sessionId)
            rememberScreenLocked(sessionId, screenName, evaluated = !late)
            if (late) return false

            var anyNewlyActivated = checkScreenOnNewSessionLocked(sessionId, personProperties)
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

                if (screenName != null && activateScreenLegLocked(group, screenName, personProperties)) {
                    anyNewlyActivated = true
                }
            }
            return anyNewlyActivated
        }
    }

    // Takes every $screen event, also one that activates nothing, so the name is never a screen the app left.
    fun rememberScreen(
        sessionId: String?,
        eventName: String,
        eventProperties: Map<String, Any?>?,
    ) {
        val screenName = screenNameOf(eventName, eventProperties) ?: return
        synchronized(lock) {
            rememberScreenLocked(sessionId, screenName, evaluated = false)
        }
    }

    // A session that started after the last $screen event gets none for the screen the app is already
    // on, so the screen leg is checked against that screen, once per session. Returns true when at
    // least one group activated. Moves activation to sessionId, so it takes the current session id only.
    // With includeUnevaluatedScreen, a screen that was only remembered is checked in its own session too.
    fun checkScreenOnNewSession(
        sessionId: String,
        personProperties: Map<String, Any?>?,
        includeUnevaluatedScreen: Boolean = false,
    ): Boolean {
        synchronized(lock) {
            return checkScreenOnNewSessionLocked(sessionId, personProperties, includeUnevaluatedScreen)
        }
    }

    // For a session that a $screen event opened: that event brings the screen, the last seen one was left.
    fun skipScreenCheck(sessionId: String) {
        synchronized(lock) {
            if (!moveActivationToSessionLocked(sessionId)) return
            screenCheckedSessionId = sessionId
            lastScreenName = null
            lastScreenUnevaluated = false
        }
    }

    private fun screenNameOf(
        eventName: String,
        eventProperties: Map<String, Any?>?,
    ): String? = if (eventName == SCREEN_EVENT_NAME) eventProperties?.get(SCREEN_NAME_PROPERTY) as? String else null

    private fun rememberScreenLocked(
        sessionId: String?,
        screenName: String?,
        evaluated: Boolean,
    ) {
        if (screenName == null) return
        // A late event of a session that was left is older than the screen of a newer session.
        val newerSessionId = lastScreenSessionId?.takeUnless { it in leftSessionIds }
        if (newerSessionId != null && sessionId in leftSessionIds) return
        lastScreenName = screenName
        lastScreenSessionId = sessionId
        lastScreenUnevaluated = !evaluated
    }

    private fun checkScreenOnNewSessionLocked(
        sessionId: String,
        personProperties: Map<String, Any?>?,
        includeUnevaluatedScreen: Boolean = false,
    ): Boolean {
        val screenName = lastScreenName ?: return false
        val sessionCovered = sessionId == lastScreenSessionId || sessionId == screenCheckedSessionId
        if (sessionCovered && !(includeUnevaluatedScreen && lastScreenUnevaluated)) return false
        if (!moveActivationToSessionLocked(sessionId)) return false
        screenCheckedSessionId = sessionId
        lastScreenUnevaluated = false
        return groups.count { activateScreenLegLocked(it, screenName, personProperties) } > 0
    }

    // Screens stand in for the web url leg.
    private fun activateScreenLegLocked(
        group: PostHogTriggerGroup,
        screenName: String,
        personProperties: Map<String, Any?>?,
    ): Boolean {
        val conditions = group.conditions
        val matched =
            conditions.urls.isNotEmpty() &&
                group.id !in screenActivatedGroupIds &&
                conditions.urls.any { it.containsMatchIn(screenName) } &&
                // The url leg has no event to read properties from, so neither does the screen leg.
                matchTriggerPropertyFilters(conditions.properties, null, personProperties)
        if (matched) {
            screenActivatedGroupIds.add(group.id)
        }
        return matched
    }

    // Read-only for activation: a caller holding a stale session id must not wipe the current
    // session's activated groups. Only onEvent, checkScreenOnNewSession and skipScreenCheck move activation
    // to a new session.
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
            val pendingConditions = mutableListOf<String>()

            for (group in groups) {
                val legs = legStatusesLocked(group, flags, activatedSession)
                when (groupStatus(group, legs)) {
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
                    PostHogTriggerStatus.PENDING -> {
                        hasPendingGroups = true
                        legs.pendingLabels().mapTo(pendingConditions) { "trigger group \"${group.name}\": $it" }
                    }
                    PostHogTriggerStatus.DISABLED -> Unit
                }
            }

            return PostHogTriggerGroupsDecision(
                shouldRecord = shouldRecord,
                hasPendingGroups = hasPendingGroups,
                minDurationMs = minDurationMs,
                groupsCount = groups.size,
                matchedGroups = matchedGroups,
                pendingConditions = pendingConditions,
            )
        }
    }

    private enum class PostHogTriggerStatus {
        ACTIVATED,
        PENDING,
        DISABLED,
    }

    private class LegStatuses(
        val event: PostHogTriggerStatus,
        val screen: PostHogTriggerStatus,
        val flag: PostHogTriggerStatus,
    ) {
        // The screen leg stands in for the url leg, which is listed first.
        fun pendingLabels(): List<String> =
            listOf(
                screen to "URL condition not matched",
                event to "event condition not matched",
                flag to "linked flag condition not matched",
            ).filter { it.first == PostHogTriggerStatus.PENDING }.map { it.second }
    }

    private fun legStatusesLocked(
        group: PostHogTriggerGroup,
        flags: Map<String, Any?>?,
        activatedSession: Boolean,
    ): LegStatuses {
        val conditions = group.conditions
        val hasEvents = conditions.hasEventsLeg
        val hasUrls = conditions.hasUrlsLeg
        val hasFlag = conditions.hasFlagLeg

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
        return LegStatuses(eventLeg, screenLeg, flagLeg)
    }

    private fun groupStatus(
        group: PostHogTriggerGroup,
        legs: LegStatuses,
    ): PostHogTriggerStatus {
        val conditions = group.conditions
        if (!conditions.hasEventsLeg && !conditions.hasUrlsLeg && !conditions.hasFlagLeg) return PostHogTriggerStatus.ACTIVATED

        return if (conditions.matchType == PostHogTriggerMatchType.ANY) {
            orTriggerStatus(legs.event, legs.screen, legs.flag)
        } else {
            andTriggerStatus(legs.event, legs.screen, legs.flag)
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
        if (stored != null && stored.sessionId == sessionId && Objects.equals(stored.sampleRate, group.sampleRate)) {
            return stored.sampled
        }

        // A missing rate samples in, like the web clampToRange fallback.
        val sampled =
            group.sampleRate?.let { rate -> sampleOnProperty(sessionId + group.id, rate) } ?: true

        samplingDecisions[group.id] = StoredSamplingDecision(sessionId, group.sampleRate, sampled)
        return sampled
    }

    // Returns false for a session that activation already left.
    private fun moveActivationToSessionLocked(sessionId: String): Boolean {
        if (activationSessionId == sessionId) return true
        if (sessionId in leftSessionIds) return false
        activationSessionId?.let { left ->
            leftSessionIds.add(left)
            if (leftSessionIds.size > MAX_LEFT_SESSIONS) {
                leftSessionIds.remove(leftSessionIds.first())
            }
        }
        activationSessionId = sessionId
        eventActivatedGroupIds.clear()
        screenActivatedGroupIds.clear()
        return true
    }

    private companion object {
        const val SCREEN_EVENT_NAME: String = "\$screen"
        const val SCREEN_NAME_PROPERTY: String = "\$screen_name"
        const val MAX_LEFT_SESSIONS: Int = 16
    }
}

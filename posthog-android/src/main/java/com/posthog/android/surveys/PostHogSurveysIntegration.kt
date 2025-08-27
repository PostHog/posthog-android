package com.posthog.android.surveys

import android.content.Context
import com.posthog.PostHogConfig
import com.posthog.PostHogIntegration
import com.posthog.PostHogInterface
import com.posthog.android.internal.getDeviceType
import com.posthog.android.internal.isMatchingRegex
import com.posthog.internal.PostHogPreferences
import com.posthog.internal.surveys.PostHogSurveysHandler
import com.posthog.surveys.OnPostHogSurveyClosed
import com.posthog.surveys.OnPostHogSurveyResponse
import com.posthog.surveys.OnPostHogSurveyShown
import com.posthog.surveys.PostHogDisplaySurvey
import com.posthog.surveys.PostHogNextSurveyQuestion
import com.posthog.surveys.PostHogSurveyResponse
import com.posthog.surveys.PostHogSurveysDefaultDelegate
import com.posthog.surveys.PostHogSurveysDelegate
import com.posthog.surveys.RatingSurveyQuestion
import com.posthog.surveys.SingleSurveyQuestion
import com.posthog.surveys.Survey
import com.posthog.surveys.SurveyMatchType
import com.posthog.surveys.SurveyQuestion
import com.posthog.surveys.SurveyQuestionBranching

public class PostHogSurveysIntegration(
    context: Context,
) : PostHogIntegration, PostHogSurveysHandler {
    private val surveyValidationMap: Map<SurveyMatchType, (List<String>, String) -> Boolean> =
        mapOf(
            SurveyMatchType.I_CONTAINS to { targets, value -> targets.any { value.contains(it, ignoreCase = true) } },
            SurveyMatchType.NOT_I_CONTAINS to { targets, value -> targets.all { !value.contains(it, ignoreCase = true) } },
            SurveyMatchType.REGEX to { targets, value -> targets.any { pattern -> isMatchingRegex(value, pattern) } },
            SurveyMatchType.NOT_REGEX to { targets, value -> targets.all { pattern -> !isMatchingRegex(value, pattern) } },
            SurveyMatchType.EXACT to { targets, value -> targets.any { value == it } },
            SurveyMatchType.IS_NOT to { targets, value -> targets.all { value != it } },
        )

    private val deviceType: String = getDeviceType(context) ?: "Mobile"

    // Thread safety locks
    private val surveysLock = Any()
    private val seenSurveysLock = Any()
    private val eventActivationLock = Any()
    private val activeSurveyLock = Any()
    private val lifecycleLock = Any()

    private var postHog: PostHogInterface? = null
    private var config: PostHogConfig? = null

    // Cached surveys pushed from PostHog Remote Config
    private var cachedSurveys: List<Survey> = emptyList()

    // Survey seen tracking
    private val surveySeenKeyPrefix = "seenSurvey_"
    private var seenSurveyKeys: MutableMap<String, Boolean>? = null

    private companion object {
        private const val NEXT_SURVEY_TRANSITION_DELAY_MS = 750L
    }

    // Event activation tracking
    private val eventActivatedSurveys = mutableSetOf<String>()
    private val eventsToSurveys = mutableMapOf<String, List<String>>()

    // Survey response tracking
    private val currentSurveyResponses = mutableMapOf<String, PostHogSurveyResponse>()
    private var activeSurveyCompleted = false

    public override fun install(postHog: PostHogInterface) {
        this.postHog = postHog
        this.config = postHog.getConfig() as? PostHogConfig

        // Start the survey integration lifecycle
        synchronized(lifecycleLock) {
            isStarted = true
        }

        showNextSurvey()
    }

    override fun uninstall() {
        // Stop the survey integration lifecycle
        synchronized(lifecycleLock) {
            isStarted = false
        }

        clearActiveSurvey()

        this.postHog = null
        this.config = null
    }

    // Push-based callback from PostHog when surveys are loaded/updated
    public override fun onSurveysLoaded(surveys: List<Survey>) {
        synchronized(surveysLock) {
            cachedSurveys = surveys
        }
        synchronized(eventActivationLock) {
            rebuildEventsToSurveysMap(surveys)
        }
        // Attempt to show a survey if we're started and none is active
        val shouldShowSurvey = synchronized(lifecycleLock) { isStarted }
        if (shouldShowSurvey) {
            showNextSurvey()
        }
    }

    /**
     * Gets the surveys delegate from the PostHog config.
     *
     * @return The surveys delegate from PostHogConfig.surveysConfig
     */
    private fun getSurveysDelegate(): PostHogSurveysDelegate {
        return config?.surveysConfig?.surveysDelegate ?: PostHogSurveysDefaultDelegate(config)
    }

    private fun defaultMatchType(matchType: SurveyMatchType?): SurveyMatchType {
        return matchType ?: SurveyMatchType.I_CONTAINS
    }

    private fun doesSurveyDeviceTypesMatch(survey: Survey): Boolean {
        val deviceTypes = survey.conditions?.deviceTypes ?: return true
        if (deviceTypes.isEmpty()) return true

        val matchType = defaultMatchType(survey.conditions?.deviceTypesMatchType)
        return surveyValidationMap[matchType]?.invoke(deviceTypes, deviceType) ?: true
    }

    private fun canActivateRepeatedly(survey: Survey): Boolean {
        return survey.conditions?.events?.repeatedActivation == true && hasEvents(survey)
    }

    /**
     * Get surveys enabled for the current user.
     * Uses the cached surveys pushed from remote config and filters for active matching surveys.
     *
     * @return List of filtered surveys
     */
    private fun getActiveMatchingSurveys(): List<Survey> {
        // Check if surveys are enabled in config
        val config = config
        if (config?.surveys != true) {
            return emptyList()
        }

        val surveys = synchronized(surveysLock) { cachedSurveys }
        return getActiveMatchingSurveys(surveys)
    }

    private fun rebuildEventsToSurveysMap(surveys: List<Survey>) {
        val eventMap = mutableMapOf<String, MutableList<String>>()
        surveys.forEach { survey ->
            survey.conditions?.events?.values?.forEach { eventCondition ->
                val eventName = eventCondition.name
                if (eventName.isNotEmpty()) {
                    eventMap.getOrPut(eventName) { mutableListOf() }.add(survey.id)
                }
            }
        }
        synchronized(eventsToSurveys) {
            eventsToSurveys.clear()
            eventsToSurveys.putAll(eventMap)
        }
    }

    private fun getActiveMatchingSurveys(surveys: List<Survey>): List<Survey> {
        val postHog = postHog ?: return emptyList()

        return surveys.filter { survey ->
            // 1. Filter out inactive surveys (must have start date and no end date)
            if (survey.startDate == null || survey.endDate != null) return@filter false

            // 2. Filter out surveys that don't match device type
            if (!doesSurveyDeviceTypesMatch(survey)) return@filter false

            // 3. Filter out seen surveys (unless they can activate repeatedly)
            if (getSurveySeen(survey)) return@filter false

            // 4. Check feature flags (collect all non-empty keys and verify they're enabled)
            val allKeys = mutableListOf<String>()

            // Linked flag key
            survey.linkedFlagKey?.takeIf { it.isNotEmpty() }?.let { allKeys.add(it) }

            // Targeting flag key
            survey.targetingFlagKey?.takeIf { it.isNotEmpty() }?.let { allKeys.add(it) }

            // Internal targeting flag key (only if survey cannot activate repeatedly)
            if (!canActivateRepeatedly(survey)) {
                survey.internalTargetingFlagKey?.takeIf { it.isNotEmpty() }?.let { allKeys.add(it) }
            }

            // Feature flag keys
            survey.featureFlagKeys?.forEach { keyVal ->
                val flagValue = keyVal.value
                if (keyVal.key.isNotEmpty() && !flagValue.isNullOrEmpty()) {
                    allKeys.add(flagValue)
                }
            }

            // All collected flag keys must be enabled
            val featureFlagsMatch = allKeys.all { postHog.isFeatureEnabled(it) }

            // 5. For event-based surveys, check if they have been activated by the event
            val eventActivationCheck =
                if (hasEvents(survey)) {
                    isSurveyEventActivated(survey)
                } else {
                    true
                }

            featureFlagsMatch && eventActivationCheck
        }
    }

    /**
     * Shows a survey to the user using the configured delegate.
     *
     * This method handles:
     * 1. Converting the internal survey model to a display model
     * 2. Setting up callbacks for survey events (shown, response, closed)
     * 3. Calling the delegate to render the survey UI
     *
     * @param survey The survey to show
     */
    internal fun showSurvey(survey: Survey) {
        // Check if we can show a survey (no active survey)
        if (!canShowNextSurvey()) {
            config?.logger?.log("Cannot show survey - another survey is already active")
            return
        }

        val displaySurvey = PostHogDisplaySurvey.toDisplaySurvey(survey)

        // Store the original survey for branching logic
        val originalSurvey = survey

        // Setup callbacks for delegate call
        val onSurveyShown: OnPostHogSurveyShown = { shownSurvey ->
            // Check if shownSurvey is originalSurvey
            if (shownSurvey.id == originalSurvey.id) {
                val currentActiveSurvey = activeSurvey

                // If currentActiveSurvey is null, set this originalSurvey as active
                if (currentActiveSurvey == null) {
                    setActiveSurvey(originalSurvey)
                }

                // Send survey shown event
                sendSurveyShownEvent(originalSurvey)

                // Clear up event-activated surveys if this survey has events
                if (hasEvents(originalSurvey)) {
                    eventActivatedSurveys.remove(originalSurvey.id)
                }
            } else {
                config?.logger?.log("Received a show event for a non-matching survey: ${shownSurvey.id} vs ${originalSurvey.id}")
            }
        }

        val onSurveyResponse: OnPostHogSurveyResponse = { responseSurvey, questionIndex, response ->
            // Get current active survey
            val currentActiveSurvey = activeSurvey

            // Validate that this survey matches the currently active survey
            if (currentActiveSurvey == null || responseSurvey.id != currentActiveSurvey.id) {
                config?.logger?.log("Received a response event for a non-active survey")
                null
            } else {
                // Calculate next question based on current response
                val nextQuestion = getNextQuestion(originalSurvey, questionIndex, response)

                // Store the response for survey completion tracking
                currentSurveyResponses[getResponseKey(questionIndex)] = response

                // Check if survey is completed (needed on close event)
                activeSurveyCompleted = nextQuestion.isSurveyCompleted

                // Send completion event if survey is finished
                if (activeSurveyCompleted) {
                    sendSurveySentEvent(originalSurvey, currentSurveyResponses)
                }

                nextQuestion
            }
        }

        val onSurveyClosed: OnPostHogSurveyClosed = onSurveyClosed@{ _ ->
            // Get current active survey and completion state
            val currentActiveSurvey = activeSurvey

            // Validate that this survey matches the currently active survey
            if (currentActiveSurvey == null || originalSurvey.id != currentActiveSurvey.id) {
                config?.logger?.log("[Surveys] Received a close event for a non-active survey")
                return@onSurveyClosed
            }

            // Send survey dismissed event if survey was not completed
            if (!activeSurveyCompleted) {
                sendSurveyDismissedEvent(originalSurvey)
            }

            // Mark survey as seen
            setSurveySeen(originalSurvey)

            // Clear active survey
            clearActiveSurvey()

            // Show next survey in queue after a short delay
            Thread {
                Thread.sleep(NEXT_SURVEY_TRANSITION_DELAY_MS)
                showNextSurvey()
            }.start()
        }

        // Call the delegate to render the survey
        getSurveysDelegate().renderSurvey(displaySurvey, onSurveyShown, onSurveyResponse, onSurveyClosed)
    }

    /**
     * Cleans up any active surveys by calling the delegate's cleanupSurveys method.
     */
    internal fun cleanupSurveys() {
        getSurveysDelegate().cleanupSurveys()
    }

    /**
     * Determines the next question to show based on the current question's branching logic.
     *
     * @param displaySurvey The current display survey
     * @param originalSurvey The original survey containing branching logic
     * @param currentIndex The index of the current question
     * @param response The user's response to the current question
     * @return The next question state
     */
    private fun getNextQuestion(
        originalSurvey: Survey,
        currentIndex: Int,
        response: PostHogSurveyResponse,
    ): PostHogNextSurveyQuestion {
        val originalQuestion = originalSurvey.questions.getOrNull(currentIndex)
        val nextQuestionIndex = minOf(currentIndex + 1, originalSurvey.questions.size - 1)

        // If no branching is defined, check if we're at the last question
        val branching = originalQuestion?.branching
        if (branching == null) {
            return PostHogNextSurveyQuestion(
                questionIndex = nextQuestionIndex,
                isSurveyCompleted = currentIndex == originalSurvey.questions.size - 1,
            )
        }

        return when (branching) {
            is SurveyQuestionBranching.End -> {
                PostHogNextSurveyQuestion(
                    questionIndex = currentIndex,
                    isSurveyCompleted = true,
                )
            }
            is SurveyQuestionBranching.Next -> {
                PostHogNextSurveyQuestion(
                    questionIndex = nextQuestionIndex,
                    isSurveyCompleted = currentIndex == originalSurvey.questions.size - 1,
                )
            }
            is SurveyQuestionBranching.SpecificQuestion -> {
                val targetIndex = minOf(branching.index, originalSurvey.questions.size - 1)
                PostHogNextSurveyQuestion(
                    questionIndex = targetIndex,
                    isSurveyCompleted = targetIndex == originalSurvey.questions.size - 1,
                )
            }
            is SurveyQuestionBranching.ResponseBased -> {
                getResponseBasedNextQuestion(
                    originalSurvey,
                    originalQuestion,
                    response,
                    branching.responseValues,
                ) ?: PostHogNextSurveyQuestion(
                    questionIndex = nextQuestionIndex,
                    isSurveyCompleted = nextQuestionIndex == originalSurvey.questions.size - 1,
                )
            }
        }
    }

    /**
     * Returns next question index based on response value, implementing iOS-style response mapping.
     */
    private fun getResponseBasedNextQuestion(
        survey: Survey,
        question: SurveyQuestion?,
        response: PostHogSurveyResponse,
        responseValues: Map<String, Any>,
    ): PostHogNextSurveyQuestion? {
        if (question == null) {
            config?.logger?.log("[Surveys] Got response based branching, but missing the actual question.")
            return null
        }

        return when (response) {
            is PostHogSurveyResponse.SingleChoice -> {
                handleSingleChoiceResponseBranching(question, response, responseValues, survey.questions.size)
            }
            is PostHogSurveyResponse.Rating -> {
                handleRatingResponseBranching(question, response, responseValues, survey.questions.size)
            }
            else -> {
                config?.logger?.log("[Surveys] Got response based branching for an unsupported question type.")
                null
            }
        }
    }

    /**
     * Handles single choice response branching with choice index mapping.
     */
    private fun handleSingleChoiceResponseBranching(
        question: SurveyQuestion,
        response: PostHogSurveyResponse.SingleChoice,
        responseValues: Map<String, Any>,
        totalQuestions: Int,
    ): PostHogNextSurveyQuestion? {
        if (question !is SingleSurveyQuestion) {
            return null
        }

        val selectedChoice = response.selectedChoice
        var responseIndex = question.choices?.indexOf(selectedChoice ?: "") ?: -1

        // If the response is not found in the choices, it might be an open choice (always last)
        if (responseIndex == -1 && question.hasOpenChoice == true) {
            responseIndex = (question.choices?.size ?: 0) - 1
        }

        if (responseIndex >= 0) {
            val nextIndex = responseValues[responseIndex.toString()]
            if (nextIndex != null) {
                return processBranchingStep(nextIndex, totalQuestions)
            }
        }

        config?.logger?.log("[Surveys] Could not find response index for specific question.")
        return null
    }

    /**
     * Handles rating response branching with rating bucket logic.
     */
    private fun handleRatingResponseBranching(
        question: SurveyQuestion,
        response: PostHogSurveyResponse.Rating,
        responseValues: Map<String, Any>,
        totalQuestions: Int,
    ): PostHogNextSurveyQuestion? {
        if (question !is RatingSurveyQuestion) {
            return null
        }

        val scale = question.scale
        val rating = response.rating
        if (scale != null && rating != null) {
            val ratingBucket = getRatingBucketForResponseValue(scale, rating)
            if (ratingBucket != null) {
                val nextIndex = responseValues[ratingBucket]
                if (nextIndex != null) {
                    return processBranchingStep(nextIndex, totalQuestions)
                }
            }
        }

        config?.logger?.log("[Surveys] Could not get response bucket for rating question.")
        return null
    }

    /**
     * Processes a branching step result, handling both Int indices and "end" string values.
     */
    private fun processBranchingStep(
        nextIndex: Any,
        totalQuestions: Int,
    ): PostHogNextSurveyQuestion? {
        return when {
            nextIndex is Int -> {
                val safeIndex = minOf(nextIndex, totalQuestions - 1)
                PostHogNextSurveyQuestion(
                    questionIndex = safeIndex,
                    isSurveyCompleted = safeIndex >= totalQuestions,
                )
            }
            nextIndex is String && nextIndex.lowercase() == "end" -> {
                PostHogNextSurveyQuestion(
                    questionIndex = totalQuestions - 1,
                    isSurveyCompleted = true,
                )
            }
            else -> null
        }
    }

    /**
     * Gets the response bucket for a given rating response value, given the scale.
     * For example, for a scale of 3, the buckets are "negative", "neutral" and "positive".
     */
    private fun getRatingBucketForResponseValue(
        scale: Int,
        value: Int,
    ): String? {
        return when (scale) {
            3 -> {
                when (value) {
                    1 -> "negative"
                    2 -> "neutral"
                    3 -> "positive"
                    else -> null
                }
            }
            5 -> {
                when (value) {
                    in 1..2 -> "negative"
                    3 -> "neutral"
                    in 4..5 -> "positive"
                    else -> null
                }
            }
            7 -> {
                when (value) {
                    in 1..3 -> "negative"
                    4 -> "neutral"
                    in 5..7 -> "positive"
                    else -> null
                }
            }
            10 -> {
                when (value) {
                    in 0..6 -> "detractors" // NPS: 0-6 are detractors
                    in 7..8 -> "passives" // NPS: 7-8 are passives
                    in 9..10 -> "promoters" // NPS: 9-10 are promoters
                    else -> null
                }
            }
            else -> null
        }
    }

    // Active survey tracking
    private var activeSurvey: Survey? = null

    // Lifecycle management
    private var isStarted: Boolean = false

    /**
     * Checks if we can show the next survey.
     * Returns true if there's no active survey currently being displayed.
     */
    internal fun canShowNextSurvey(): Boolean {
        return synchronized(activeSurveyLock) {
            activeSurvey == null
        }
    }

    /**
     * Shows the next available survey if one exists and can be shown.
     * This method:
     * 1. Checks if we can show a survey (no active survey)
     * 2. Gets all active matching surveys
     * 3. Shows the first available survey using the delegate
     * 4. Tracks the active survey state
     */
    internal fun showNextSurvey() {
        if (!canShowNextSurvey()) {
            return
        }

        // Use cached surveys pushed from remote config
        val activeSurveys = getActiveMatchingSurveys()

        // Find the first survey that can be rendered
        val surveyToShow = activeSurveys.firstOrNull()

        if (surveyToShow != null) {
            // Use the existing showSurvey method which handles all the logic
            showSurvey(surveyToShow)
        }
    }

    /**
     * Sets the currently active survey.
     * This prevents multiple surveys from being shown simultaneously.
     */
    private fun setActiveSurvey(survey: Survey?) {
        synchronized(activeSurveyLock) {
            activeSurvey = survey
            activeSurveyCompleted = false
            currentSurveyResponses.clear()
        }
    }

    /**
     * Clears the active survey when it's completed or dismissed.
     * This allows the next survey to be shown.
     */
    private fun clearActiveSurvey() {
        synchronized(activeSurveyLock) {
            activeSurvey = null
            activeSurveyCompleted = false
            currentSurveyResponses.clear()
        }
    }

    // Survey Event Methods

    /**
     * Sends a "survey shown" event to PostHog instance
     */
    private fun sendSurveyShownEvent(survey: Survey) {
        sendSurveyEvent(
            event = "survey shown",
            survey = survey,
        )
    }

    /**
     * Sends a "survey sent" event to PostHog instance
     * Sends a survey completion event to PostHog with all collected responses
     * @param survey The completed survey
     * @param responses Map of collected responses for each question
     */
    private fun sendSurveySentEvent(
        survey: Survey,
        responses: Map<String, PostHogSurveyResponse>,
    ) {
        val questionProperties =
            mutableMapOf<String, Any>(
                "\$survey_questions" to survey.questions.map { it.question },
            )

        // Add survey interaction property for "responded"
        questionProperties["\$set"] =
            mapOf(
                getSurveyInteractionProperty(survey, "responded") to true,
            )

        // Convert responses to simple values
        val responsesProperties =
            responses.mapNotNull { (key, response) ->
                response.toResponseValue()?.let { value ->
                    key to value
                }
            }.toMap()

        val additionalProperties = questionProperties + responsesProperties

        sendSurveyEvent(
            event = "survey sent",
            survey = survey,
            additionalProperties = additionalProperties,
        )
    }

    /**
     * Sends a "survey dismissed" event to PostHog instance
     */
    private fun sendSurveyDismissedEvent(survey: Survey) {
        val additionalProperties =
            mapOf(
                "\$survey_questions" to survey.questions.map { it.question },
                "\$set" to
                    mapOf(
                        getSurveyInteractionProperty(survey, "dismissed") to true,
                    ),
            )

        sendSurveyEvent(
            event = "survey dismissed",
            survey = survey,
            additionalProperties = additionalProperties,
        )
    }

    /**
     * Helper method to send survey events with consistent properties
     */
    private fun sendSurveyEvent(
        event: String,
        survey: Survey,
        additionalProperties: Map<String, Any> = emptyMap(),
    ) {
        val postHog =
            postHog ?: run {
                return
            }

        val properties = getBaseSurveyEventProperties(survey).toMutableMap()
        properties.putAll(additionalProperties)

        postHog.capture(event, properties = properties)
    }

    /**
     * Get base properties for survey events
     */
    private fun getBaseSurveyEventProperties(survey: Survey): Map<String, Any> {
        val props = mutableMapOf<String, Any>()

        props["\$survey_name"] = survey.name
        props["\$survey_id"] = survey.id

        survey.currentIteration?.let { iteration ->
            props["\$survey_iteration"] = iteration
        }

        survey.currentIterationStartDate?.let { startDate ->
            props["\$survey_iteration_start_date"] = startDate
        }

        return props
    }

    /**
     * Generate survey interaction property key for tracking user interactions
     */
    private fun getSurveyInteractionProperty(
        survey: Survey,
        property: String,
    ): String {
        val currentIteration = survey.currentIteration

        return if (currentIteration != null && currentIteration > 0) {
            "\$survey_$property/${survey.id}/$currentIteration"
        } else {
            "\$survey_$property/${survey.id}"
        }
    }

    /**
     * Generate the property key used to store a response for a given question index.
     * For index 0 returns "$survey_response", otherwise returns "$survey_response_<index>".
     */
    private fun getResponseKey(index: Int): String {
        return if (index == 0) {
            "\$survey_response"
        } else {
            "\$survey_response_$index"
        }
    }

    // Seen Survey Tracking Methods

    /**
     * Returns the computed storage key for a given survey
     */
    private fun getSurveySeenKey(survey: Survey): String {
        val surveySeenKey = "${surveySeenKeyPrefix}${survey.id}"
        val currentIteration = survey.currentIteration
        return if (currentIteration != null && currentIteration > 0) {
            "${surveySeenKey}_$currentIteration"
        } else {
            surveySeenKey
        }
    }

    /**
     * Returns survey seen list (loads from disk if memory cache is empty)
     */
    private fun getSeenSurveyKeys(): Map<String, Boolean> {
        if (seenSurveyKeys == null) {
            val postHog = postHog
            val config = postHog?.getConfig() as? PostHogConfig

            @Suppress("UNCHECKED_CAST")
            val storedKeys = config?.cachePreferences?.getValue(PostHogPreferences.SURVEY_SEEN) as? Map<String, Boolean>
            seenSurveyKeys = storedKeys?.toMutableMap() ?: mutableMapOf()
        }
        return seenSurveyKeys ?: emptyMap()
    }

    /**
     * Checks storage for seenSurvey_ key and returns its value
     * Note: if the survey can be repeatedly activated by its events, this value will default to false
     */
    private fun getSurveySeen(survey: Survey): Boolean {
        if (canActivateRepeatedly(survey)) {
            // if this survey can activate repeatedly, we override this return value
            return false
        }

        val key = getSurveySeenKey(survey)
        val seenKeys = synchronized(seenSurveysLock) { getSeenSurveyKeys() }
        return seenKeys[key] ?: false
    }

    /**
     * Mark a survey as seen and persist to disk
     */
    private fun setSurveySeen(survey: Survey) {
        val key = getSurveySeenKey(survey)

        synchronized(seenSurveysLock) {
            // Ensure we're working with the current cache
            val currentKeys = getSeenSurveyKeys().toMutableMap()
            currentKeys[key] = true
            seenSurveyKeys = currentKeys

            // Persist to disk immediately
            val postHog = postHog
            val config = postHog?.getConfig() as? PostHogConfig
            config?.cachePreferences?.setValue(PostHogPreferences.SURVEY_SEEN, currentKeys)
        }
    }

    // Event Activation Methods

    /**
     * Checks if a survey has been previously activated by an associated event
     */
    private fun isSurveyEventActivated(survey: Survey): Boolean {
        return synchronized(eventActivationLock) {
            eventActivatedSurveys.contains(survey.id)
        }
    }

    /**
     * Called when an event is captured to activate associated surveys
     */
    public override fun onEvent(event: String) {
        val activatedSurveys =
            synchronized(eventActivationLock) {
                // Copy to avoid concurrent modification issues
                eventsToSurveys[event]?.toList()
            } ?: return
        if (activatedSurveys.isEmpty()) return

        synchronized(eventActivationLock) {
            for (surveyId in activatedSurveys) {
                eventActivatedSurveys.add(surveyId)
            }
        }

        // Trigger survey display
        val shouldShowSurvey = synchronized(lifecycleLock) { isStarted }
        if (shouldShowSurvey) {
            showNextSurvey()
        }
    }

    /**
     * Check if a survey has events defined
     */
    private fun hasEvents(survey: Survey): Boolean {
        return survey.conditions?.events?.values?.isNotEmpty() == true
    }
}

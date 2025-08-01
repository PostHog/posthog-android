package com.posthog.android.surveys

import android.content.Context
import com.posthog.PostHogConfig
import com.posthog.PostHogIntegration
import com.posthog.PostHogInterface
import com.posthog.surveys.OnPostHogSurveyClosed
import com.posthog.surveys.OnPostHogSurveyResponse
import com.posthog.surveys.OnPostHogSurveyShown
import com.posthog.surveys.Survey
import com.posthog.surveys.SurveyMatchType
import com.posthog.surveys.PostHogSurveysDelegate
import com.posthog.surveys.PostHogSurveysDefaultDelegate
import com.posthog.surveys.PostHogDisplaySurvey
import com.posthog.surveys.PostHogDisplaySurveyAppearance
import com.posthog.surveys.PostHogDisplayOpenQuestion
import com.posthog.surveys.PostHogDisplayRatingQuestion
import com.posthog.surveys.PostHogDisplaySurveyRatingType
import com.posthog.surveys.PostHogDisplaySurveyTextContentType
import com.posthog.surveys.PostHogNextSurveyQuestion
import com.posthog.surveys.PostHogDisplayChoiceQuestion
import com.posthog.surveys.PostHogDisplayLinkQuestion
import com.posthog.surveys.PostHogSurveyResponse
import com.posthog.surveys.SurveyType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.sql.Date

public class PostHogSurveysIntegration(
    private val context: Context? = null
) : PostHogIntegration {
    private val surveyValidationMap: Map<SurveyMatchType, (List<String>, String) -> Boolean> =
        mapOf(
            SurveyMatchType.I_CONTAINS to { targets, value -> targets.any { value.contains(it, ignoreCase = true) } },
            SurveyMatchType.NOT_I_CONTAINS to { targets, value -> targets.all { !value.contains(it, ignoreCase = true) } },
            SurveyMatchType.REGEX to { targets, value -> targets.any { 
                try { 
                    value.matches(Regex(it)) 
                } catch (e: Exception) { 
                    false 
                } 
            } },
            SurveyMatchType.NOT_REGEX to { targets, value -> targets.all { 
                try { 
                    !value.matches(Regex(it)) 
                } catch (e: Exception) { 
                    true 
                } 
            } },
            SurveyMatchType.EXACT to { targets, value -> targets.any { value == it } },
            SurveyMatchType.IS_NOT to { targets, value -> targets.all { value != it } },
        )

    private val deviceType: String = "Mobile"

    private var postHog: PostHogInterface? = null

    public override fun install(postHog: PostHogInterface) {
        this.postHog = postHog
        renderMockSurvey()
    }
    
    /**
     * Renders a mock survey for testing purposes.
     * This method creates and displays a sample survey through the delegate after a 3-second delay.
     */
    @Suppress("ktlint:standard:value-argument-comment")
    private fun renderMockSurvey() {
        val mockSurvey: PostHogDisplaySurvey = createMockRatingSurvey(useCustomAppearance = true)
        val delegate: PostHogSurveysDelegate = getSurveysDelegate()
        
        // Launch coroutine with 3-second delay before showing survey
        CoroutineScope(Dispatchers.Main).launch {
            delay(3000) // 3 seconds delay
            
            delegate.renderSurvey(
                survey = mockSurvey,
                onSurveyShown = { _ ->
                    // Mock survey shown callback
                },
                onSurveyResponse = { _, index, _ ->
                    // Mock survey response callback
                    PostHogNextSurveyQuestion(
                        questionIndex = index,
                        isSurveyCompleted = true
                    )
                },
                onSurveyClosed = { _ ->
                    // Mock survey closed callback
                }
            )
        }
    }
    
    /**
     * Creates a mock survey for testing purposes.
     * 
     * @param useCustomAppearance Whether to use custom appearance styling
     * @return A mock PostHogDisplaySurvey with sample data
     */
    private fun createMockSurvey(useCustomAppearance: Boolean = false): PostHogDisplaySurvey {
        val mockQuestion: PostHogDisplayOpenQuestion = PostHogDisplayOpenQuestion(
            question = "How would you rate your experience with our app?",
            questionDescription = "Please provide your honest feedback to help us improve.",
            questionDescriptionContentType = PostHogDisplaySurveyTextContentType.TEXT,
            isOptional = false,
            buttonText = "Submit"
        )
        
        return PostHogDisplaySurvey(
            id = "mock-survey-123",
            name = "Mock Survey for Testing",
            questions = listOf(mockQuestion),
            appearance = if (useCustomAppearance) createMockCustomAppearance() else null,
            startDate = Date(System.currentTimeMillis()),
            endDate = null
        )
    }
    
    /**
     * Creates a mock survey with rating question for testing purposes.
     * 
     * @param useCustomAppearance Whether to use custom appearance styling
     * @return A mock PostHogDisplaySurvey with rating question
     */
    private fun createMockRatingSurvey(useCustomAppearance: Boolean = false): PostHogDisplaySurvey {
        val mockRatingQuestion: PostHogDisplayRatingQuestion = PostHogDisplayRatingQuestion(
            question = "How likely are you to recommend our app to a friend?",
            questionDescription = "Please rate on a scale from 0 to 10.",
            questionDescriptionContentType = PostHogDisplaySurveyTextContentType.TEXT,
            isOptional = false,
            buttonText = "Submit Rating",
            ratingType = PostHogDisplaySurveyRatingType.NUMBER,
            scaleLowerBound = 0,
            scaleUpperBound = 10,
            lowerBoundLabel = "Not likely",
            upperBoundLabel = "Very likely"
        )
        
        return PostHogDisplaySurvey(
            id = "mock-rating-survey-456",
            name = "Mock Rating Survey for Testing",
            questions = listOf(mockRatingQuestion),
            appearance = if (useCustomAppearance) createMockCustomAppearance() else null,
            startDate = Date(System.currentTimeMillis()),
            endDate = null
        )
    }
    
    /**
     * Creates a mock survey with emoji rating question for testing purposes.
     * 
     * @param useCustomAppearance Whether to use custom appearance styling
     * @return A mock PostHogDisplaySurvey with emoji rating 1-5
     */
    private fun createMockEmojiSurvey(useCustomAppearance: Boolean = false): PostHogDisplaySurvey {
        val mockEmojiQuestion: PostHogDisplayRatingQuestion = PostHogDisplayRatingQuestion(
            question = "How would you rate your experience with our app?",
            questionDescription = "Please select an emoji from 1 to 5.",
            questionDescriptionContentType = PostHogDisplaySurveyTextContentType.TEXT,
            isOptional = false,
            buttonText = "Submit Rating",
            ratingType = PostHogDisplaySurveyRatingType.EMOJI,
            scaleLowerBound = 1,
            scaleUpperBound = 5,
            lowerBoundLabel = "Poor",
            upperBoundLabel = "Excellent"
        )
        
        return PostHogDisplaySurvey(
            id = "mock-emoji-survey-789",
            name = "Mock Emoji Survey for Testing",
            questions = listOf(mockEmojiQuestion),
            appearance = if (useCustomAppearance) createMockCustomAppearance() else null,
            startDate = Date(System.currentTimeMillis()),
            endDate = null
        )
    }
    
    /**
     * Creates a mock survey with single choice question for testing purposes.
     * 
     * @param hasOpenChoice Whether the single choice question has an open choice option
     * @param useCustomAppearance Whether to use custom appearance styling
     * @return A mock PostHogDisplaySurvey with single choice question
     */
    private fun createMockSingleChoiceSurvey(hasOpenChoice: Boolean = false, useCustomAppearance: Boolean = false): PostHogDisplaySurvey {
        val mockSingleChoiceQuestion: PostHogDisplayChoiceQuestion = PostHogDisplayChoiceQuestion(
            question = "What is your preferred communication method?",
            questionDescription = "Please select one option.",
            questionDescriptionContentType = PostHogDisplaySurveyTextContentType.TEXT,
            isOptional = false,
            buttonText = "Continue",
            choices = listOf("Email", "Phone", "SMS", "In-app notifications"),
            hasOpenChoice = hasOpenChoice,
            shuffleOptions = false,
            isMultipleChoice = false
        )
        
        return PostHogDisplaySurvey(
            id = "mock-single-choice-survey-101",
            name = "Mock Single Choice Survey for Testing",
            questions = listOf(mockSingleChoiceQuestion),
            appearance = if (useCustomAppearance) createMockCustomAppearance() else null,
            startDate = Date(System.currentTimeMillis()),
            endDate = null
        )
    }
    
    /**
     * Creates a mock survey with multiple choice question for testing purposes.
     * 
     * @return A mock PostHogDisplaySurvey with multiple choice question
     */
    private fun createMockMultipleChoiceSurvey(hasOpenChoice: Boolean = false, useCustomAppearance: Boolean = false): PostHogDisplaySurvey {
        val mockMultipleChoiceQuestion: PostHogDisplayChoiceQuestion = PostHogDisplayChoiceQuestion(
            question = "Which features do you use most often?",
            questionDescription = "Select all that apply.",
            questionDescriptionContentType = PostHogDisplaySurveyTextContentType.TEXT,
            isOptional = false,
            buttonText = "Submit",
            choices = listOf("Dashboard", "Analytics", "Reports", "Settings", "User Management"),
            hasOpenChoice = hasOpenChoice,
            shuffleOptions = false,
            isMultipleChoice = true
        )
        
        return PostHogDisplaySurvey(
            id = "mock-multiple-choice-survey-202",
            name = "Mock Multiple Choice Survey for Testing",
            questions = listOf(mockMultipleChoiceQuestion),
            appearance = if (useCustomAppearance) createMockCustomAppearance() else null,
            startDate = Date(System.currentTimeMillis()),
            endDate = null
        )
    }
    
    /**
     * Creates a mock survey with link question for testing purposes.
     * 
     * @return A mock PostHogDisplaySurvey with link question
     */
    private fun createMockLinkSurvey(): PostHogDisplaySurvey {
        val mockLinkQuestion: PostHogDisplayLinkQuestion = PostHogDisplayLinkQuestion(
            question = "Would you like to learn more about our premium features?",
            questionDescription = "Click the link below to visit our features page.",
            questionDescriptionContentType = PostHogDisplaySurveyTextContentType.TEXT,
            isOptional = true,
            buttonText = "Visit Features Page",
            link = "https://posthog.com/features"
        )
        
        return PostHogDisplaySurvey(
            id = "mock-link-survey-303",
            name = "Mock Link Survey for Testing",
            questions = listOf(mockLinkQuestion),
            appearance = null,
            startDate = Date(System.currentTimeMillis()),
            endDate = null
        )
    }
    
    /**
     * Creates a custom mock appearance for testing purposes.
     * 
     * @return A PostHogDisplaySurveyAppearance with custom styling
     */
    private fun createMockCustomAppearance(): PostHogDisplaySurveyAppearance {
        return PostHogDisplaySurveyAppearance(
            fontFamily = "Arial, sans-serif",
            backgroundColor = "#f8f9fa",
            borderColor = "#dee2e6",
            submitButtonColor = "#007bff",
            submitButtonText = "Submit Response",
            submitButtonTextColor = "#ffffff",
            descriptionTextColor = "#6c757d",
            ratingButtonColor = "#e9ecef",
            ratingButtonActiveColor = "#007bff",
            placeholder = "Enter your response here...",
            displayThankYouMessage = true,
            thankYouMessageHeader = "Thank you!",
            thankYouMessageDescription = "Your feedback is valuable to us."
        )
    }
    
    /**
     * Gets the surveys delegate from the PostHog config.
     * 
     * @return The surveys delegate from PostHogConfig.surveysConfig
     */
    private fun getSurveysDelegate(): PostHogSurveysDelegate {
        val config = postHog?.getConfig() as? PostHogConfig
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
        if (survey.type == SurveyType.WIDGET) {
            return true
        }

        return survey.conditions?.events?.repeatedActivation ?: true
    }

    private fun getActiveMatchingSurveys(surveys: List<Survey>): List<Survey> {
        return surveys.filter { survey ->
            if (survey.startDate == null || survey.endDate != null) return@filter false

            if (!doesSurveyDeviceTypesMatch(survey)) return@filter false

            // TODO: add support for seen surveys

            if (survey.linkedFlagKey.isNullOrEmpty() &&
                survey.targetingFlagKey.isNullOrEmpty() &&
                survey.internalTargetingFlagKey.isNullOrEmpty() &&
                survey.featureFlagKeys.isNullOrEmpty()
            ) {
                return@filter true
            }

            val postHog = postHog ?: return@filter false

            val overrideInternalTargetingFlagCheck = canActivateRepeatedly(survey)
            val linkedFlagCheck = survey.linkedFlagKey?.let { postHog.isFeatureEnabled(it) } ?: true
            val targetingFlagCheck = survey.targetingFlagKey?.let { postHog.isFeatureEnabled(it) } ?: true
            val internalTargetingFlagKey = survey.internalTargetingFlagKey
            val internalTargetingFlagCheck =
                overrideInternalTargetingFlagCheck ||
                    if (!internalTargetingFlagKey.isNullOrEmpty()) {
                        postHog.isFeatureEnabled(internalTargetingFlagKey)
                    } else {
                        true
                    }

            val flagsCheck =
                survey.featureFlagKeys?.all { keyVal ->
                    val key = keyVal.key
                    val value = keyVal.value
                    key.isEmpty() || value.isNullOrEmpty() || postHog.isFeatureEnabled(value)
                } ?: true

            linkedFlagCheck && targetingFlagCheck && internalTargetingFlagCheck && flagsCheck
        }
    }
    
    /**
     * Shows a survey to the user using the configured delegate.
     * 
     * This method handles:
     * 1. Converting the internal survey model to a display model
     * 2. Setting up callbacks for survey events (shown, response, closed)
     * 3. Calling the delegate to render the survey UI
     * 4. Sending appropriate analytics events
     * 5. Handling survey navigation logic
     *
     * @param survey The survey to show
     */
    public fun showSurvey(survey: Survey) {
        val postHog = postHog ?: return
        val displaySurvey = PostHogDisplaySurvey.toDisplaySurvey(survey)
        
        // Store the original survey for branching logic
        val originalSurvey = survey
        
        // Create callbacks for the delegate
        val onSurveyShown: OnPostHogSurveyShown = { shownSurvey ->
            // Send survey shown event
            postHog.capture(
                event = "survey shown",
                properties = mapOf(
                    "survey_id" to shownSurvey.id,
                    "survey_name" to shownSurvey.name
                )
            )
        }
        
        val onSurveyResponse: OnPostHogSurveyResponse = { responseSurvey, questionIndex, response ->
            if (questionIndex < 0 || questionIndex >= responseSurvey.questions.size) {
                PostHogNextSurveyQuestion(
                    questionIndex = questionIndex,
                    isSurveyCompleted = false
                )
            } else {
//                val displayQuestion = responseSurvey.questions[questionIndex]
                
                // Find the original survey question to get its type for event properties
//                val originalQuestion = originalSurvey.questions.getOrNull(questionIndex)
//                val questionType = originalQuestion?.type?.value ?: ""
//                val questionText = originalQuestion?.question ?: displayQuestion.question
                
                // Send survey response event
//                postHog.capture(
//                    event = "survey response",
//                    properties = mapOf(
//                        "survey_id" to responseSurvey.id,
//                        "survey_name" to responseSurvey.name,
//                        "question" to questionText,
//                        "question_type" to questionType,
//                        "survey_response" to response.toResponseValue()
//                    )
//                )
                
                // Determine next question
                getNextQuestion(responseSurvey, originalSurvey, questionIndex, response)
            }
        }
        
        val onSurveyClosed: OnPostHogSurveyClosed = { closedSurvey ->
            // Send survey closed event
            postHog.capture(
                event = "survey closed",
                properties = mapOf(
                    "survey_id" to closedSurvey.id,
                    "survey_name" to closedSurvey.name
                )
            )
            
            // Clear the active survey to allow the next survey to be shown
            clearActiveSurvey()
        }
        
        // Call the delegate to render the survey
        getSurveysDelegate().renderSurvey(displaySurvey, onSurveyShown, onSurveyResponse, onSurveyClosed)
    }
    
    /**
     * Cleans up any active surveys by calling the delegate's cleanupSurveys method.
     */
    public fun cleanupSurveys() {
        getSurveysDelegate().cleanupSurveys()
    }
    
    /**
     * Gets the choices for a question if it's a choice-based question.
     *
     * @param question The survey question or null
     * @return List of choices or null if not applicable
     */
    private fun getChoicesForQuestion(question: com.posthog.surveys.SurveyQuestion?): List<String>? {
        return when (question) {
            is com.posthog.surveys.SingleSurveyQuestion -> question.choices
            is com.posthog.surveys.MultipleSurveyQuestion -> question.choices
            else -> null
        }
    }
    
    /**
     * Determines the next question to show based on the current question's branching logic.
     *
     * @param displaySurvey The current display survey
     * @param originalSurvey The original survey containing branching logic
     * @param currentIndex The index of the current question
     * @param response The user's response to the current question
     * @return The next question state or null if there's an error
     */
    private fun getNextQuestion(
        displaySurvey: PostHogDisplaySurvey,
        originalSurvey: Survey,
        currentIndex: Int,
        response: PostHogSurveyResponse
    ): PostHogNextSurveyQuestion {
        if (currentIndex < 0 || currentIndex >= displaySurvey.questions.size) {
            return PostHogNextSurveyQuestion(
                questionIndex = currentIndex,
                isSurveyCompleted = true
            )
        }
        
        val originalQuestion = originalSurvey.questions.getOrNull(currentIndex)
        val branching = originalQuestion?.branching
        
        // If no branching is defined, go to the next question
        if (branching == null) {
            val nextIndex = currentIndex + 1
            return PostHogNextSurveyQuestion(
                questionIndex = nextIndex,
                isSurveyCompleted = nextIndex >= displaySurvey.questions.size
            )
        }
        
        // Handle branching logic based on the branching type
        val nextIndex = when (branching) {
            is com.posthog.surveys.SurveyQuestionBranching.End -> {
                // End the survey
                originalSurvey.questions.size
            }
            is com.posthog.surveys.SurveyQuestionBranching.SpecificQuestion -> {
                // Go to a specific question
                val targetIndex = branching.index
                if (targetIndex < 0 || targetIndex >= originalSurvey.questions.size) {
                    // Invalid target index, go to the next question
                    currentIndex + 1
                } else {
                    targetIndex
                }
            }
            is com.posthog.surveys.SurveyQuestionBranching.Next -> {
                // Go to the next question
                currentIndex + 1
            }
            is com.posthog.surveys.SurveyQuestionBranching.ResponseBased -> {
                // Determine the next question based on the response
                val responseValue = when (response) {
                    is PostHogSurveyResponse.Text -> {
                        // Text responses don't have response-based branching
                        null
                    }
                    is PostHogSurveyResponse.Rating -> {
                        response.rating.toString()
                    }
                    is PostHogSurveyResponse.SingleChoice -> {
                        response.selectedChoice?.toString()
                    }
                    is PostHogSurveyResponse.MultipleChoice -> {
                        // For multiple choice, we only use the first selected choice for branching
                        if (response.selectedChoices?.isNotEmpty() == true) {
                            response.selectedChoices?.first()
                        } else {
                            null
                        }
                    }
                    is PostHogSurveyResponse.Link -> {
                        // Link responses don't have response-based branching
                        null
                    }
                }
                
                if (responseValue != null && branching.responseValues.containsKey(responseValue)) {
                    val targetIndex = branching.responseValues[responseValue] as? Int
                    if (targetIndex != null && targetIndex >= 0 && targetIndex < originalSurvey.questions.size) {
                        targetIndex
                    } else {
                        // Invalid target index, go to the next question
                        currentIndex + 1
                    }
                } else {
                    // No matching branch, go to the next question
                    currentIndex + 1
                }
            }
        }
        
        return PostHogNextSurveyQuestion(
            questionIndex = nextIndex,
            isSurveyCompleted = nextIndex >= displaySurvey.questions.size
        )
    }
    
    // Active survey tracking
    private var activeSurvey: Survey? = null
    
    // Lifecycle and throttling management
    private var lastLayoutTriggerTime: Long = 0
    private val layoutTriggerThrottleMs: Long = 5000 // 5 seconds throttle, similar to iOS
    private var isStarted: Boolean = false
    
    // Lifecycle management - following PostHogLifecycleObserverIntegration pattern
    private var lifecycleInstalled: Boolean = false
    
    /**
     * Checks if we can show the next survey.
     * Returns true if there's no active survey currently being displayed.
     */
    public fun canShowNextSurvey(): Boolean {
        return activeSurvey == null
    }
    
    /**
     * Shows the next available survey if one exists and can be shown.
     * This method:
     * 1. Checks if we can show a survey (no active survey)
     * 2. Gets all active matching surveys
     * 3. Shows the first available survey using the delegate
     * 4. Tracks the active survey state
     */
    public fun showNextSurvey() {
//        val postHog = postHog ?: return
        
        if (!canShowNextSurvey()) {
            return
        }

        // TODO:
    }
    
    /**
     * Sets the currently active survey.
     * This prevents multiple surveys from being shown simultaneously.
     */
    private fun setActiveSurvey(survey: Survey?) {
        activeSurvey = survey
    }
    
    /**
     * Clears the active survey when it's completed or dismissed.
     * This allows the next survey to be shown.
     */
    private fun clearActiveSurvey() {
        activeSurvey = null
    }
    
    /**
     * Starts the survey integration lifecycle.
     * This should be called when the integration is ready to start showing surveys.
     * In a real Android app, this would typically be called from Application.onCreate()
     * or when the main activity starts.
     */
    public fun start() {
        if (lifecycleInstalled) {
            return
        }
        lifecycleInstalled = true
        isStarted = true
        
        // Note: In a real Android app, you would register lifecycle callbacks here
        // Following the pattern from PostHogLifecycleObserverIntegration:
        // lifecycle.addObserver(this)
        
        // Trigger initial survey check when starting
        onAppBecameActive()
    }
    
    /**
     * Stops the survey integration lifecycle.
     * This should be called when the integration should stop showing surveys.
     */
    public fun stop() {
        lifecycleInstalled = false
        isStarted = false
        clearActiveSurvey()
        
        // Note: In a real Android app, you would unregister lifecycle callbacks here
        // Following the pattern from PostHogLifecycleObserverIntegration:
        // lifecycle.removeObserver(this)
    }
    
    /**
     * Called when the app becomes active (foreground).
     * This triggers showNextSurvey() similar to the iOS implementation.
     * Should be called from Activity.onResume() or Application lifecycle callbacks.
     */
    public fun onAppBecameActive() {
        if (!isStarted) return
        showNextSurvey()
    }
    
    /**
     * Called when there are layout changes in the app.
     * This triggers showNextSurvey() with throttling to prevent excessive calls.
     * Should be called from ViewTreeObserver.OnGlobalLayoutListener or similar.
     */
    public fun onLayoutChanged() {
        if (!isStarted) return
        
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastLayoutTriggerTime >= layoutTriggerThrottleMs) {
            lastLayoutTriggerTime = currentTime
            showNextSurvey()
        }
    }
    
    // Activity lifecycle management
    // Note: In a real Android app, you would implement Activity.LifecycleCallbacks here
    // and register them with the Application to automatically trigger onAppBecameActive()
    
    /**
     * Registers the lifecycle observer with the application context.
     * This method should be called by the host application to enable automatic
     * survey triggering when the app becomes active.
     * 
     * Usage in Application class:
     * ```kotlin
     * class MyApplication : Application() {
     *     override fun onCreate() {
     *         super.onCreate()
     *         val surveysIntegration = PostHogSurveysIntegration(this)
     *         surveysIntegration.start()
     *         surveysIntegration.registerWithApplicationLifecycle(this)
     *     }
     * }
     * ```
     */

//    public fun registerWithApplicationLifecycle(application: Any) {
        // This method provides a way for the host application to register
        // the lifecycle callbacks. The actual implementation would depend on
        // the Android Application.registerActivityLifecycleCallbacks() method.
        // 
        // In a real implementation, this would be:
        // if (application is Application) {
        //     application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
        //         override fun onActivityResumed(activity: Activity) {
        //             activityLifecycleCallbacks.onActivityResumed()
        //         }
        //         override fun onActivityPaused(activity: Activity) {
        //             activityLifecycleCallbacks.onActivityPaused()
        //         }
        //         // Other lifecycle methods...
        //     })
        // }
//    }
    
    /**
     * Manually triggers the app became active event.
     * This can be called directly by the host application when it detects
     * that the app has become active, as an alternative to automatic lifecycle detection.
     */
    public fun triggerAppBecameActive() {
        onAppBecameActive()
    }
}

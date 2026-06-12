package com.posthog.surveys

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class SurveyAppearanceMappingTest {
    private fun appearance(popupDelaySeconds: Double?): SurveyAppearance =
        SurveyAppearance(
            position = null,
            fontFamily = null,
            backgroundColor = null,
            submitButtonColor = null,
            submitButtonText = null,
            submitButtonTextColor = null,
            textColor = null,
            descriptionTextColor = null,
            ratingButtonColor = null,
            ratingButtonActiveColor = null,
            ratingButtonHoverColor = null,
            inputBackground = null,
            inputTextColor = null,
            whiteLabel = null,
            autoDisappear = null,
            displayThankYouMessage = null,
            thankYouMessageHeader = null,
            thankYouMessageDescription = null,
            thankYouMessageDescriptionContentType = null,
            thankYouMessageCloseButtonText = null,
            borderColor = "#E5E5EA",
            placeholder = null,
            shuffleQuestions = null,
            surveyPopupDelaySeconds = popupDelaySeconds,
            widgetType = null,
            widgetSelector = null,
            widgetLabel = null,
            widgetColor = null,
        )

    @Test
    fun `surveyPopupDelaySeconds is mapped onto the display appearance`() {
        val display = PostHogDisplaySurveyAppearance.fromSurveyAppearance(appearance(2.5))

        assertEquals(2.5, display.surveyPopupDelaySeconds)
    }

    @Test
    fun `null popup delay maps to null`() {
        val display = PostHogDisplaySurveyAppearance.fromSurveyAppearance(appearance(null))

        assertNull(display.surveyPopupDelaySeconds)
    }
}

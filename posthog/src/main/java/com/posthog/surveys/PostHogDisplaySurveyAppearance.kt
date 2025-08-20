package com.posthog.surveys

/**
 * Represents the appearance configuration for a survey.
 *
 * @property fontFamily Optional font family to use throughout the survey
 * @property backgroundColor Optional background color as web color (e.g. "#FFFFFF" or "white")
 * @property borderColor Optional border color as web color
 * @property submitButtonColor Optional background color for the submit button as web color
 * @property submitButtonText Optional custom text for the submit button
 * @property submitButtonTextColor Optional text color for the submit button as web color
 * @property descriptionTextColor Optional color for description text as web color
 * @property ratingButtonColor Optional color for rating buttons as web color
 * @property ratingButtonActiveColor Optional color for active/selected rating buttons as web color
 * @property placeholder Optional placeholder text for input fields
 * @property displayThankYouMessage Whether to show a thank you message after survey completion
 * @property thankYouMessageHeader Optional header text for the thank you message
 * @property thankYouMessageDescription Optional description text for the thank you message
 * @property thankYouMessageDescriptionContentType Optional content type for the thank you message description
 */
public data class PostHogDisplaySurveyAppearance(
    val fontFamily: String? = null,
    val backgroundColor: String? = null,
    val borderColor: String? = null,
    val submitButtonColor: String? = null,
    val submitButtonText: String? = null,
    val submitButtonTextColor: String? = null,
    val descriptionTextColor: String? = null,
    val ratingButtonColor: String? = null,
    val ratingButtonActiveColor: String? = null,
    val placeholder: String? = null,
    val displayThankYouMessage: Boolean = false,
    val thankYouMessageHeader: String? = null,
    val thankYouMessageDescription: String? = null,
    val thankYouMessageDescriptionContentType: PostHogDisplaySurveyTextContentType? = null,
) {
    internal companion object {
        /**
         * Creates a PostHogDisplaySurveyAppearance from a SurveyAppearance object
         *
         * @param appearance The SurveyAppearance object to convert
         * @return A new PostHogDisplaySurveyAppearance instance
         */
        internal fun fromSurveyAppearance(appearance: SurveyAppearance): PostHogDisplaySurveyAppearance {
            val thankYouContentType =
                if (appearance.thankYouMessageDescriptionContentType?.value == "html") {
                    PostHogDisplaySurveyTextContentType.HTML
                } else {
                    PostHogDisplaySurveyTextContentType.TEXT
                }

            return PostHogDisplaySurveyAppearance(
                fontFamily = appearance.fontFamily,
                backgroundColor = appearance.backgroundColor,
                borderColor = appearance.borderColor,
                submitButtonColor = appearance.submitButtonColor,
                submitButtonText = appearance.submitButtonText,
                submitButtonTextColor = appearance.submitButtonTextColor,
                descriptionTextColor = appearance.descriptionTextColor,
                ratingButtonColor = appearance.ratingButtonColor,
                ratingButtonActiveColor = appearance.ratingButtonActiveColor,
                placeholder = appearance.placeholder,
                displayThankYouMessage = appearance.displayThankYouMessage ?: false,
                thankYouMessageHeader = appearance.thankYouMessageHeader,
                thankYouMessageDescription = appearance.thankYouMessageDescription,
                thankYouMessageDescriptionContentType = thankYouContentType,
            )
        }
    }
}

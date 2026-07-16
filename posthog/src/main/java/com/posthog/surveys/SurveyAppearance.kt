package com.posthog.surveys

/**
 * Visual styling and behavior configuration for how a survey is presented.
 *
 * Colors are CSS color strings (for example "#1d4aff" or "white"). Some fields only
 * apply to specific survey or question types, as noted per property.
 */
public data class SurveyAppearance(
    /** Where the survey popup is anchored on screen. */
    val position: SurveyAppearancePosition?,
    /** The font family used for survey text. */
    val fontFamily: String?,
    /** The background color of the survey. */
    val backgroundColor: String?,
    /** The background color of the submit button. */
    val submitButtonColor: String?,
    /** The label shown on the submit button. */
    val submitButtonText: String?,
    /** The text color of the submit button. */
    val submitButtonTextColor: String?,
    /** The color of the survey's primary text. */
    val textColor: String?,
    /** The color of question description text. */
    val descriptionTextColor: String?,
    /** The color of rating buttons in their default, unselected state. */
    val ratingButtonColor: String?,
    /** The color of a rating button once it is selected. */
    val ratingButtonActiveColor: String?,
    /** The color of a rating button while hovered (web only). */
    val ratingButtonHoverColor: String?,
    /** The background color of text input fields. */
    val inputBackground: String?,
    /** The text color of text input fields. */
    val inputTextColor: String?,
    /** When true, hides PostHog branding on the survey. */
    val whiteLabel: Boolean?,
    /**
     * Whether the thank-you message auto-dismisses a few seconds after the survey is submitted.
     * Only applies when [displayThankYouMessage] is enabled.
     */
    val autoDisappear: Boolean?,
    /** Whether to show a confirmation message after the survey is submitted. */
    val displayThankYouMessage: Boolean?,
    /** The header text of the confirmation message. */
    val thankYouMessageHeader: String?,
    /** The body text of the confirmation message. */
    val thankYouMessageDescription: String?,
    /** Whether the confirmation message description is rendered as HTML or plain text. */
    val thankYouMessageDescriptionContentType: SurveyTextContentType?,
    /** The label on the confirmation message's close button. */
    val thankYouMessageCloseButtonText: String?,
    /** The color of the survey's border. */
    val borderColor: String,
    /** The placeholder text shown in empty text input fields. */
    val placeholder: String?,
    /** Whether to randomize the order in which questions are presented. */
    val shuffleQuestions: Boolean?,
    /** The delay, in seconds, before the survey popup appears. */
    val surveyPopupDelaySeconds: Double?,
    /** For widget surveys, how the trigger widget is presented (button, tab, or selector). */
    val widgetType: SurveyAppearanceWidgetType?,
    /** For selector widgets, the CSS selector of the element that triggers the survey. */
    val widgetSelector: String?,
    /** The label shown on the survey's trigger widget. */
    val widgetLabel: String?,
    /** The color of the survey's trigger widget. */
    val widgetColor: String?,
)

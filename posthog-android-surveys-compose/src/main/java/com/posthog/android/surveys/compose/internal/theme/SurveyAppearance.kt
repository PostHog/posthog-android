package com.posthog.android.surveys.compose.internal.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import com.posthog.surveys.PostHogDisplaySurveyAppearance
import com.posthog.surveys.PostHogDisplaySurveyTextContentType

/**
 * Resolved appearance applied to every survey composable in a tree.
 *
 * Built by [PostHogDisplaySurveyAppearance.resolve], which fills in a default
 * for every field the customer left unset.
 */
internal data class ResolvedSurveyAppearance(
    val backgroundColor: Color,
    val borderColor: Color,
    val submitButtonColor: Color,
    val submitButtonTextColor: Color,
    val submitButtonText: String,
    val textColor: Color,
    val questionTextColor: Color,
    val descriptionTextColor: Color,
    val placeholder: String,
    val placeholderTextColor: Color,
    val inputBackgroundColor: Color,
    val inputTextColor: Color,
    val ratingButtonColor: Color,
    val ratingButtonActiveColor: Color,
    val displayThankYouMessage: Boolean,
    val thankYouMessageHeader: String,
    val thankYouMessageDescription: String?,
    val thankYouMessageDescriptionContentType: PostHogDisplaySurveyTextContentType,
    val thankYouMessageCloseButtonText: String,
)

internal val LocalSurveyAppearance =
    compositionLocalOf<ResolvedSurveyAppearance> {
        error(
            "LocalSurveyAppearance not provided; wrap survey content in " +
                "SurveyAppearanceProvider",
        )
    }

@Composable
@ReadOnlyComposable
internal fun localAppearance(): ResolvedSurveyAppearance = LocalSurveyAppearance.current

/**
 * Default light-mode background when the survey did not specify one.
 *
 * A slightly off-white value that reads well in light mode without forcing a
 * Material theme on the host app. Dark-mode polish is deferred to a follow-up.
 */
private val DefaultBackgroundColor = Color(0xFFF2F2F7)
private val DefaultBorderColor = Color(0xFFE5E5EA)
private val DefaultRatingBackground = Color(0xFFE5E5EA)
private val DefaultDescriptionTextColor = Color(0xFF8E8E93)

// On a light survey background the input field uses a subtle gray (rather than
// white) so it stays visible against the survey background; see resolve().
private val DefaultInputBackgroundOnLight = Color(0xFFF8F8F8)

private const val DEFAULT_PLACEHOLDER = "Start typing..."
private const val DEFAULT_THANK_YOU_HEADER = "Thank you for your feedback!"
private const val DEFAULT_THANK_YOU_CLOSE = "Close"

internal fun PostHogDisplaySurveyAppearance?.resolve(): ResolvedSurveyAppearance {
    val backgroundColor =
        parseSurveyColorOrDefault(this?.backgroundColor, DefaultBackgroundColor)
    val submitButtonColor =
        parseSurveyColorOrDefault(this?.submitButtonColor, Color.Black)
    val submitButtonTextColor =
        parseSurveyColorOrDefault(this?.submitButtonTextColor, submitButtonColor.contrastingTextColor())
    val textColor =
        parseSurveyColorOrDefault(this?.textColor, backgroundColor.contrastingTextColor())
    val descriptionTextColor =
        parseSurveyColorOrDefault(this?.descriptionTextColor, DefaultDescriptionTextColor)
    val borderColor =
        parseSurveyColorOrDefault(this?.borderColor, DefaultBorderColor)
    val ratingButtonColor =
        parseSurveyColorOrDefault(this?.ratingButtonColor, DefaultRatingBackground)
    val ratingButtonActiveColor =
        parseSurveyColorOrDefault(this?.ratingButtonActiveColor, submitButtonColor)
    val submitButtonText = this?.submitButtonText?.takeIf { it.isNotBlank() } ?: "Submit"
    // The question header uses the background's contrasting color. We surface it as an explicit
    // field so each composable can read it without reaching back into the appearance struct.
    val questionTextColor = backgroundColor.contrastingTextColor()

    // Input field colors, when unset: the input background is a subtle gray on a light survey
    // background (so the field stays visible) and white otherwise; the input text color
    // contrasts that background.
    val inputBackgroundColor =
        parseSurveyColorOrDefault(
            this?.inputBackground,
            if (backgroundColor.isLight()) DefaultInputBackgroundOnLight else Color.White,
        )
    val inputTextColor =
        parseSurveyColorOrDefault(this?.inputTextColor, inputBackgroundColor.contrastingTextColor())
    val placeholderTextColor = inputTextColor.copy(alpha = 0.5f)
    val placeholder = this?.placeholder?.takeIf { it.isNotBlank() } ?: DEFAULT_PLACEHOLDER

    val displayThankYouMessage = this?.displayThankYouMessage ?: false
    val thankYouMessageHeader =
        this?.thankYouMessageHeader?.takeIf { it.isNotBlank() } ?: DEFAULT_THANK_YOU_HEADER
    val thankYouMessageDescription = this?.thankYouMessageDescription?.takeIf { it.isNotBlank() }
    val thankYouMessageDescriptionContentType =
        this?.thankYouMessageDescriptionContentType ?: PostHogDisplaySurveyTextContentType.TEXT
    val thankYouMessageCloseButtonText =
        this?.thankYouMessageCloseButtonText?.takeIf { it.isNotBlank() } ?: DEFAULT_THANK_YOU_CLOSE

    return ResolvedSurveyAppearance(
        backgroundColor = backgroundColor,
        borderColor = borderColor,
        submitButtonColor = submitButtonColor,
        submitButtonTextColor = submitButtonTextColor,
        submitButtonText = submitButtonText,
        textColor = textColor,
        questionTextColor = questionTextColor,
        descriptionTextColor = descriptionTextColor,
        placeholder = placeholder,
        placeholderTextColor = placeholderTextColor,
        inputBackgroundColor = inputBackgroundColor,
        inputTextColor = inputTextColor,
        ratingButtonColor = ratingButtonColor,
        ratingButtonActiveColor = ratingButtonActiveColor,
        displayThankYouMessage = displayThankYouMessage,
        thankYouMessageHeader = thankYouMessageHeader,
        thankYouMessageDescription = thankYouMessageDescription,
        thankYouMessageDescriptionContentType = thankYouMessageDescriptionContentType,
        thankYouMessageCloseButtonText = thankYouMessageCloseButtonText,
    )
}

private fun parseSurveyColorOrDefault(
    input: String?,
    default: Color,
): Color {
    val parsed = parseSurveyColor(input)
    return if (parsed == Color.Transparent && input.isNullOrBlank()) default else parsed
}

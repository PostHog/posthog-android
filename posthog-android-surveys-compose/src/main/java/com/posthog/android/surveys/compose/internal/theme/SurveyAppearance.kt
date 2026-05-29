package com.posthog.android.surveys.compose.internal.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import com.posthog.surveys.PostHogDisplaySurveyAppearance

/**
 * Resolved appearance applied to every survey composable in a tree.
 *
 * Built by [PostHogDisplaySurveyAppearance.resolve] which fills in defaults
 * matching the iOS reference (see `SurveySheet.swift` ::
 * `SwiftUISurveyAppearance.getAppearanceWithDefaults`).
 */
internal data class ResolvedSurveyAppearance(
    val backgroundColor: Color,
    val borderColor: Color,
    val submitButtonColor: Color,
    val submitButtonTextColor: Color,
    val submitButtonText: String,
    val textColor: Color,
    val descriptionTextColor: Color,
    val ratingButtonColor: Color,
    val ratingButtonActiveColor: Color,
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
 * iOS uses `Color(.tertiarySystemBackground)`. We pick a slightly off-white
 * value that reads well in light mode without forcing a Material theme on the
 * host app. Dark-mode polish is explicitly deferred to a follow-up.
 */
private val DefaultBackgroundColor = Color(0xFFF2F2F7)
private val DefaultBorderColor = Color(0xFFE5E5EA)
private val DefaultRatingBackground = Color(0xFFE5E5EA)
private val DefaultDescriptionTextColor = Color(0xFF8E8E93)

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

    return ResolvedSurveyAppearance(
        backgroundColor = backgroundColor,
        borderColor = borderColor,
        submitButtonColor = submitButtonColor,
        submitButtonTextColor = submitButtonTextColor,
        submitButtonText = submitButtonText,
        textColor = textColor,
        descriptionTextColor = descriptionTextColor,
        ratingButtonColor = ratingButtonColor,
        ratingButtonActiveColor = ratingButtonActiveColor,
    )
}

private fun parseSurveyColorOrDefault(
    input: String?,
    default: Color,
): Color {
    val parsed = parseSurveyColor(input)
    return if (parsed == Color.Transparent && input.isNullOrBlank()) default else parsed
}

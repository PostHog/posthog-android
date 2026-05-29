package com.posthog.android.surveys.compose.internal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.posthog.android.surveys.compose.internal.theme.LocalSurveyAppearance
import com.posthog.android.surveys.compose.internal.theme.resolve
import com.posthog.android.surveys.compose.internal.ui.BottomSection
import com.posthog.android.surveys.compose.internal.ui.NumberRating
import com.posthog.android.surveys.compose.internal.ui.QuestionHeader
import com.posthog.surveys.PostHogDisplayRatingQuestion
import com.posthog.surveys.PostHogDisplaySurveyAppearance
import com.posthog.surveys.PostHogDisplaySurveyRatingType
import com.posthog.surveys.PostHogDisplaySurveyTextContentType

private val previewQuestion =
    PostHogDisplayRatingQuestion(
        id = "preview-q",
        question = "How likely are you to recommend us to a friend?",
        questionDescription = "0 means not at all likely, 10 means extremely likely.",
        questionDescriptionContentType = PostHogDisplaySurveyTextContentType.TEXT,
        isOptional = false,
        buttonText = "Submit",
        ratingType = PostHogDisplaySurveyRatingType.NUMBER,
        scaleLowerBound = 0,
        scaleUpperBound = 10,
        lowerBoundLabel = "Not likely",
        upperBoundLabel = "Extremely likely",
    )

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun PreviewNumberRatingDefault() {
    val appearance = remember { (null as PostHogDisplaySurveyAppearance?).resolve() }
    var rating by remember { mutableStateOf<Int?>(null) }
    CompositionLocalProvider(LocalSurveyAppearance provides appearance) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(appearance.backgroundColor)
                    .padding(16.dp),
        ) {
            QuestionHeader(previewQuestion)
            NumberRating(
                question = previewQuestion,
                selectedValue = rating,
                onSelect = { rating = it },
            )
            BottomSection(label = "Submit", enabled = rating != null) { }
        }
    }
}

@Preview(showBackground = true, widthDp = 360, name = "Themed (pastel + orange)")
@Composable
private fun PreviewNumberRatingThemed() {
    val themedAppearance =
        remember {
            PostHogDisplaySurveyAppearance(
                backgroundColor = "#FFE5B4",
                submitButtonColor = "#FF6B35",
                submitButtonText = "Send feedback",
                ratingButtonColor = "#FFF3D6",
                ratingButtonActiveColor = "#FF6B35",
                borderColor = "#F4C28C",
            ).resolve()
        }
    var rating by remember { mutableStateOf<Int?>(7) }
    CompositionLocalProvider(LocalSurveyAppearance provides themedAppearance) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(themedAppearance.backgroundColor)
                    .padding(16.dp),
        ) {
            QuestionHeader(previewQuestion)
            NumberRating(
                question = previewQuestion,
                selectedValue = rating,
                onSelect = { rating = it },
            )
            BottomSection(label = themedAppearance.submitButtonText, enabled = rating != null) { }
        }
    }
}

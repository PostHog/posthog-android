package com.posthog.android.surveys.compose.internal.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.posthog.android.surveys.compose.internal.theme.LocalSurveyAppearance
import com.posthog.android.surveys.compose.internal.theme.localAppearance
import com.posthog.android.surveys.compose.internal.theme.resolve
import com.posthog.surveys.PostHogDisplayLinkQuestion
import com.posthog.surveys.PostHogDisplaySurveyAppearance
import com.posthog.surveys.PostHogDisplaySurveyTextContentType

/**
 * Body content for [PostHogDisplayLinkQuestion]s.
 *
 * Visual port of iOS `LinkQuestionView` — link questions render no body
 * between the question header and the submit button on iOS. We surface the
 * destination URL as small description-coloured text so users have context
 * for the action; the iOS-mirrored response payload is unaffected.
 *
 * The submit action itself lives in `SurveySheet.kt`, which calls [openLink]
 * with [PostHogDisplayLinkQuestion.link] when the user presses the button.
 */
@Composable
internal fun LinkQuestion(question: PostHogDisplayLinkQuestion) {
    val appearance = localAppearance()
    val link = question.link
    if (!link.isNullOrBlank()) {
        Text(text = link, color = appearance.descriptionTextColor)
    }
}

/**
 * Opens [url] in the device's default browser.
 *
 * No-ops for blank / null URLs and swallows [android.content.ActivityNotFoundException]
 * for devices without a browser installed — the survey flow continues either way.
 */
internal fun openLink(
    context: Context,
    url: String?,
) {
    if (url.isNullOrBlank()) return
    runCatching {
        val intent =
            Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        context.startActivity(intent)
    }
}

private val previewLinkQuestion =
    PostHogDisplayLinkQuestion(
        id = "preview-link",
        question = "Want to learn more?",
        questionDescription = "Open our docs for a deeper dive.",
        questionDescriptionContentType = PostHogDisplaySurveyTextContentType.TEXT,
        isOptional = false,
        buttonText = "Open",
        link = "https://posthog.com/docs/surveys",
    )

@Preview(showBackground = true, widthDp = 360, name = "Default")
@Composable
private fun PreviewLinkQuestionDefault() {
    val appearance = remember { (null as PostHogDisplaySurveyAppearance?).resolve() }
    CompositionLocalProvider(LocalSurveyAppearance provides appearance) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(appearance.backgroundColor)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            QuestionHeader(previewLinkQuestion)
            LinkQuestion(question = previewLinkQuestion)
            BottomSection(label = "Open", enabled = true) { }
        }
    }
}

@Preview(showBackground = true, widthDp = 360, name = "Themed")
@Composable
private fun PreviewLinkQuestionThemed() {
    val appearance =
        remember {
            PostHogDisplaySurveyAppearance(
                backgroundColor = "#FFE5B4",
                submitButtonColor = "#FF6B35",
                descriptionTextColor = "#7B4F1D",
            ).resolve()
        }
    CompositionLocalProvider(LocalSurveyAppearance provides appearance) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(appearance.backgroundColor)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            QuestionHeader(previewLinkQuestion)
            LinkQuestion(question = previewLinkQuestion)
            BottomSection(label = "Open docs", enabled = true) { }
        }
    }
}

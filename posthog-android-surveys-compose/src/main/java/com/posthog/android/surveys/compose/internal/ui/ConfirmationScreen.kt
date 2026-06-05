package com.posthog.android.surveys.compose.internal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.posthog.android.surveys.compose.internal.theme.LocalSurveyAppearance
import com.posthog.android.surveys.compose.internal.theme.localAppearance
import com.posthog.android.surveys.compose.internal.theme.resolve
import com.posthog.surveys.PostHogDisplaySurveyAppearance
import com.posthog.surveys.PostHogDisplaySurveyTextContentType

/**
 * "Thank you" screen displayed after the last question of a survey when
 * [com.posthog.android.surveys.compose.internal.theme.ResolvedSurveyAppearance.displayThankYouMessage]
 * is true.
 *
 * Renders the configured header, an optional plain-text description (HTML
 * descriptions are deferred to a follow-up), and a close button.
 */
@Composable
internal fun ConfirmationScreen(onClose: () -> Unit) {
    val appearance = localAppearance()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = appearance.thankYouMessageHeader,
            color = appearance.questionTextColor,
            fontWeight = FontWeight.Bold,
        )
        val description = appearance.thankYouMessageDescription
        if (!description.isNullOrBlank() &&
            appearance.thankYouMessageDescriptionContentType == PostHogDisplaySurveyTextContentType.TEXT
        ) {
            Text(text = description, color = appearance.questionTextColor)
        }
        Spacer(modifier = Modifier.height(20.dp))
        BottomSection(
            label = appearance.thankYouMessageCloseButtonText,
            enabled = true,
            onClick = onClose,
        )
    }
}

@Preview(showBackground = true, widthDp = 360, name = "Default")
@Composable
private fun PreviewConfirmationDefault() {
    val appearance =
        remember {
            PostHogDisplaySurveyAppearance(displayThankYouMessage = true).resolve()
        }
    CompositionLocalProvider(LocalSurveyAppearance provides appearance) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(appearance.backgroundColor)
                    .padding(16.dp),
        ) {
            ConfirmationScreen(onClose = { })
        }
    }
}

@Preview(showBackground = true, widthDp = 360, name = "Themed with description")
@Composable
private fun PreviewConfirmationThemed() {
    val appearance =
        remember {
            PostHogDisplaySurveyAppearance(
                backgroundColor = "#FFE5B4",
                submitButtonColor = "#FF6B35",
                displayThankYouMessage = true,
                thankYouMessageHeader = "We appreciate your feedback!",
                thankYouMessageDescription = "Your input helps us build a better product.",
                thankYouMessageDescriptionContentType = PostHogDisplaySurveyTextContentType.TEXT,
                thankYouMessageCloseButtonText = "Done",
            ).resolve()
        }
    CompositionLocalProvider(LocalSurveyAppearance provides appearance) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(appearance.backgroundColor)
                    .padding(16.dp),
        ) {
            ConfirmationScreen(onClose = { })
        }
    }
}

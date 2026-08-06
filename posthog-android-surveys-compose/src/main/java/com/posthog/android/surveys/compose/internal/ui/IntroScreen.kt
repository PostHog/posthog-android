package com.posthog.android.surveys.compose.internal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.posthog.android.surveys.compose.internal.theme.LocalSurveyAppearance
import com.posthog.android.surveys.compose.internal.theme.localAppearance
import com.posthog.android.surveys.compose.internal.theme.resolve
import com.posthog.surveys.PostHogDisplaySurveyAppearance
import com.posthog.surveys.PostHogDisplaySurveyTextContentType

/**
 * Intro screen displayed before the first question of a survey when
 * [com.posthog.android.surveys.compose.internal.theme.ResolvedSurveyAppearance.displayIntroScreen]
 * is true — the leading mirror of [ConfirmationScreen].
 *
 * Renders the configured header, an optional plain-text description (HTML
 * descriptions are deferred to a follow-up), and a button that advances to the
 * first question. Advancing records no response and sends no survey event.
 */
@Composable
internal fun IntroScreen(onStart: () -> Unit) {
    val appearance = localAppearance()
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val header = appearance.introScreenHeader
        if (!header.isNullOrBlank()) {
            Text(
                text = header,
                color = appearance.questionTextColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
        val description = appearance.introScreenDescription
        if (!description.isNullOrBlank() &&
            appearance.introScreenDescriptionContentType == PostHogDisplaySurveyTextContentType.TEXT
        ) {
            Text(
                text = description,
                color = appearance.questionTextColor,
                textAlign = TextAlign.Center,
            )
        }
        BottomSection(
            label = appearance.introScreenButtonText,
            enabled = true,
            modifier = Modifier.padding(top = 20.dp),
            onClick = onStart,
        )
    }
}

@Preview(showBackground = true, widthDp = 360, name = "Default")
@Composable
private fun PreviewIntroDefault() {
    val appearance =
        remember {
            PostHogDisplaySurveyAppearance(
                displayIntroScreen = true,
                introScreenHeader = "Welcome!",
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
            IntroScreen(onStart = { })
        }
    }
}

@Preview(showBackground = true, widthDp = 360, name = "Themed with description")
@Composable
private fun PreviewIntroThemed() {
    val appearance =
        remember {
            PostHogDisplaySurveyAppearance(
                backgroundColor = "#FFE5B4",
                submitButtonColor = "#FF6B35",
                displayIntroScreen = true,
                introScreenHeader = "Before you start",
                introScreenDescription = "Two quick questions about your experience.",
                introScreenDescriptionContentType = PostHogDisplaySurveyTextContentType.TEXT,
                introScreenButtonText = "Let's go",
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
            IntroScreen(onStart = { })
        }
    }
}

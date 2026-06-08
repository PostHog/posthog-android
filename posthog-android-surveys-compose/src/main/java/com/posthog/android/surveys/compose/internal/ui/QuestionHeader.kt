package com.posthog.android.surveys.compose.internal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.posthog.android.surveys.compose.internal.theme.localAppearance
import com.posthog.surveys.PostHogDisplaySurveyQuestion
import com.posthog.surveys.PostHogDisplaySurveyTextContentType

@Composable
internal fun QuestionHeader(question: PostHogDisplaySurveyQuestion) {
    val appearance = localAppearance()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = question.question,
            color = appearance.questionTextColor,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        val description = question.questionDescription
        // HTML descriptions are deferred to a follow-up; only plain text renders.
        if (!description.isNullOrBlank() &&
            question.questionDescriptionContentType == PostHogDisplaySurveyTextContentType.TEXT
        ) {
            Text(
                text = description,
                color = appearance.descriptionTextColor,
                fontSize = 14.sp,
            )
        }
    }
}

package com.posthog.android.surveys.compose.internal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import com.posthog.surveys.PostHogDisplayChoiceQuestion
import com.posthog.surveys.PostHogDisplaySurveyAppearance
import com.posthog.surveys.PostHogDisplaySurveyTextContentType

/**
 * Multi-select list renderer for [PostHogDisplayChoiceQuestion]s where
 * [PostHogDisplayChoiceQuestion.isMultipleChoice] is `true`.
 *
 * Visual port of iOS `MultipleChoiceQuestionView` — delegates to the shared
 * [ChoiceOptions] composable with multi-selection semantics.
 */
@Composable
internal fun MultipleChoice(
    question: PostHogDisplayChoiceQuestion,
    selectedChoices: Set<String>,
    onSelectedChoicesChange: (Set<String>) -> Unit,
    openChoiceInput: String,
    onOpenChoiceInputChange: (String) -> Unit,
) {
    ChoiceOptions(
        options = question.choices,
        hasOpenChoice = question.hasOpenChoice,
        allowsMultipleSelection = true,
        selectedOptions = selectedChoices,
        onSelectedOptionsChange = onSelectedChoicesChange,
        openChoiceInput = openChoiceInput,
        onOpenChoiceInputChange = onOpenChoiceInputChange,
    )
}

private val previewMultipleChoice =
    PostHogDisplayChoiceQuestion(
        id = "preview-multi",
        question = "What kinds of content are you most interested in?",
        questionDescription = "Select all that apply.",
        questionDescriptionContentType = PostHogDisplaySurveyTextContentType.TEXT,
        isOptional = false,
        buttonText = "Submit",
        choices = listOf("Tutorials", "Customer case studies", "Product announcements", "Other"),
        hasOpenChoice = true,
        shuffleOptions = false,
        isMultipleChoice = true,
    )

@Preview(showBackground = true, widthDp = 360, name = "Default")
@Composable
private fun PreviewMultipleChoiceDefault() {
    val appearance = remember { (null as PostHogDisplaySurveyAppearance?).resolve() }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var openInput by remember { mutableStateOf("") }
    CompositionLocalProvider(LocalSurveyAppearance provides appearance) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(appearance.backgroundColor)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            QuestionHeader(previewMultipleChoice)
            MultipleChoice(
                question = previewMultipleChoice,
                selectedChoices = selected,
                onSelectedChoicesChange = { selected = it },
                openChoiceInput = openInput,
                onOpenChoiceInputChange = { openInput = it },
            )
            BottomSection(label = "Submit", enabled = selected.isNotEmpty()) { }
        }
    }
}

@Preview(showBackground = true, widthDp = 360, name = "Themed")
@Composable
private fun PreviewMultipleChoiceThemed() {
    val appearance =
        remember {
            PostHogDisplaySurveyAppearance(
                backgroundColor = "#FFE5B4",
                submitButtonColor = "#FF6B35",
                borderColor = "#F4C28C",
            ).resolve()
        }
    var selected by remember { mutableStateOf<Set<String>>(setOf("Tutorials", "Product announcements")) }
    var openInput by remember { mutableStateOf("") }
    CompositionLocalProvider(LocalSurveyAppearance provides appearance) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(appearance.backgroundColor)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            QuestionHeader(previewMultipleChoice)
            MultipleChoice(
                question = previewMultipleChoice,
                selectedChoices = selected,
                onSelectedChoicesChange = { selected = it },
                openChoiceInput = openInput,
                onOpenChoiceInputChange = { openInput = it },
            )
            BottomSection(label = "Send", enabled = selected.isNotEmpty()) { }
        }
    }
}

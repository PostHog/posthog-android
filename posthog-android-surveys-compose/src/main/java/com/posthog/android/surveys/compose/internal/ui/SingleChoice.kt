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
 * Single-choice list renderer for [PostHogDisplayChoiceQuestion]s where
 * [PostHogDisplayChoiceQuestion.isMultipleChoice] is `false`.
 *
 * Delegates to the shared [ChoiceOptions] composable with single-selection
 * semantics.
 *
 * State is hoisted: callers own [selectedChoice] and the optional
 * [openChoiceInput] string. When [PostHogDisplayChoiceQuestion.hasOpenChoice]
 * is true the last entry in `question.choices` becomes the open-choice option.
 */
@Composable
internal fun SingleChoice(
    question: PostHogDisplayChoiceQuestion,
    selectedChoice: String?,
    onSelectedChoiceChange: (String?) -> Unit,
    openChoiceInput: String,
    onOpenChoiceInputChange: (String) -> Unit,
) {
    val selectedSet = selectedChoice?.let { setOf(it) } ?: emptySet()
    ChoiceOptions(
        options = question.choices,
        hasOpenChoice = question.hasOpenChoice,
        allowsMultipleSelection = false,
        selectedOptions = selectedSet,
        onSelectedOptionsChange = { newSet -> onSelectedChoiceChange(newSet.firstOrNull()) },
        openChoiceInput = openChoiceInput,
        onOpenChoiceInputChange = onOpenChoiceInputChange,
    )
}

private val previewSingleChoice =
    PostHogDisplayChoiceQuestion(
        id = "preview-single",
        question = "Which feature do you use most?",
        questionDescription = null,
        questionDescriptionContentType = PostHogDisplaySurveyTextContentType.TEXT,
        isOptional = false,
        buttonText = "Submit",
        choices = listOf("Tutorials", "Customer case studies", "Product announcements", "Other"),
        hasOpenChoice = true,
        shuffleOptions = false,
        isMultipleChoice = false,
    )

@Preview(showBackground = true, widthDp = 360, name = "Default")
@Composable
private fun PreviewSingleChoiceDefault() {
    val appearance = remember { (null as PostHogDisplaySurveyAppearance?).resolve() }
    var selected by remember { mutableStateOf<String?>(null) }
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
            QuestionHeader(previewSingleChoice)
            SingleChoice(
                question = previewSingleChoice,
                selectedChoice = selected,
                onSelectedChoiceChange = { selected = it },
                openChoiceInput = openInput,
                onOpenChoiceInputChange = { openInput = it },
            )
            BottomSection(label = "Submit", enabled = selected != null) { }
        }
    }
}

@Preview(showBackground = true, widthDp = 360, name = "Themed")
@Composable
private fun PreviewSingleChoiceThemed() {
    val appearance =
        remember {
            PostHogDisplaySurveyAppearance(
                backgroundColor = "#FFE5B4",
                submitButtonColor = "#FF6B35",
                borderColor = "#F4C28C",
            ).resolve()
        }
    var selected by remember { mutableStateOf<String?>("Tutorials") }
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
            QuestionHeader(previewSingleChoice)
            SingleChoice(
                question = previewSingleChoice,
                selectedChoice = selected,
                onSelectedChoiceChange = { selected = it },
                openChoiceInput = openInput,
                onOpenChoiceInputChange = { openInput = it },
            )
            BottomSection(label = "Send", enabled = selected != null) { }
        }
    }
}

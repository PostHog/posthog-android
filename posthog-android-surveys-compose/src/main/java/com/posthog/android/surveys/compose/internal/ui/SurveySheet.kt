package com.posthog.android.surveys.compose.internal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.posthog.android.surveys.compose.internal.theme.LocalSurveyAppearance
import com.posthog.android.surveys.compose.internal.theme.localAppearance
import com.posthog.android.surveys.compose.internal.theme.resolve
import com.posthog.surveys.PostHogDisplayChoiceQuestion
import com.posthog.surveys.PostHogDisplayLinkQuestion
import com.posthog.surveys.PostHogDisplayOpenQuestion
import com.posthog.surveys.PostHogDisplayRatingQuestion
import com.posthog.surveys.PostHogDisplaySurvey
import com.posthog.surveys.PostHogDisplaySurveyQuestion
import com.posthog.surveys.PostHogSurveyResponse
import kotlinx.coroutines.launch

/**
 * Top-level survey UI: a Material 3 [ModalBottomSheet] containing the active
 * question.
 *
 * MVP renders [PostHogDisplayRatingQuestion]s only. All other question types
 * render an informative placeholder so the sheet never crashes mid-survey
 * if a customer previews a non-supported type — they're tracked as
 * follow-up work in `posthog-android-surveys-compose/CHANGELOG.md`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SurveySheet(
    survey: PostHogDisplaySurvey,
    onSurveyShown: () -> Unit,
    onSubmit: (questionIndex: Int, response: PostHogSurveyResponse) -> Boolean,
    onClose: () -> Unit,
) {
    val appearance = remember(survey) { survey.appearance.resolve() }
    val coroutineScope = rememberCoroutineScope()

    // ModalBottomSheet is auto-presented; we intercept Hidden swipe-down so
    // dismissal only happens through the explicit X button. This matches the
    // iOS implementation's `interactiveDismissDisabled()` semantics.
    val sheetState =
        rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden },
        )

    var currentQuestionIndex by remember { mutableStateOf(0) }
    val question = survey.questions.getOrNull(currentQuestionIndex)

    LaunchedEffect(survey.id) {
        onSurveyShown()
    }

    if (question == null) {
        // Defensive: an empty survey would otherwise leave the sheet stuck open.
        LaunchedEffect(Unit) { onClose() }
        return
    }

    val dismissSheet: () -> Unit = {
        coroutineScope.launch {
            sheetState.hide()
            onClose()
        }
        Unit
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = appearance.backgroundColor,
        contentColor = appearance.textColor,
        contentWindowInsets = { WindowInsets.navigationBars },
        properties =
            ModalBottomSheetProperties(
                shouldDismissOnBackPress = false,
            ),
    ) {
        CompositionLocalProvider(LocalSurveyAppearance provides appearance) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(
                                PaddingValues(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = 48.dp,
                                    bottom = 16.dp,
                                ),
                            ),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    QuestionContent(
                        question = question,
                        questionIndex = currentQuestionIndex,
                        onSubmit = onSubmit,
                        onAdvance = { next ->
                            if (next == null) {
                                dismissSheet()
                            } else {
                                currentQuestionIndex = next
                            }
                        },
                    )
                }
                IconButton(
                    onClick = dismissSheet,
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .background(appearance.backgroundColor),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close survey",
                        tint = appearance.textColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun QuestionContent(
    question: PostHogDisplaySurveyQuestion,
    questionIndex: Int,
    onSubmit: (Int, PostHogSurveyResponse) -> Boolean,
    onAdvance: (nextIndex: Int?) -> Unit,
) {
    when (question) {
        is PostHogDisplayRatingQuestion -> {
            RatingQuestion(
                question = question,
                onSubmit = { response ->
                    val completed = onSubmit(questionIndex, response)
                    if (completed) onAdvance(null)
                },
            )
        }
        is PostHogDisplayOpenQuestion,
        is PostHogDisplayLinkQuestion,
        is PostHogDisplayChoiceQuestion,
        -> {
            UnsupportedQuestionPlaceholder(buttonLabel = question.buttonText ?: "Close")
        }
        else -> UnsupportedQuestionPlaceholder(buttonLabel = "Close")
    }
}

@Composable
private fun RatingQuestion(
    question: PostHogDisplayRatingQuestion,
    onSubmit: (PostHogSurveyResponse) -> Unit,
) {
    var rating by remember(question.id) { mutableStateOf<Int?>(null) }
    val canSubmit = question.isOptional || rating != null

    QuestionHeader(question)
    NumberRating(
        question = question,
        selectedValue = rating,
        onSelect = { rating = it },
    )
    BottomSection(
        label = question.buttonText ?: localAppearanceSubmitLabel(),
        enabled = canSubmit,
        onClick = { onSubmit(PostHogSurveyResponse.Rating(rating)) },
    )
}

@Composable
private fun UnsupportedQuestionPlaceholder(buttonLabel: String) {
    val appearance = localAppearance()
    Text(
        text =
            "This question type is not yet supported by the Compose UI module. " +
                "Implement a custom PostHogSurveysDelegate or wait for a follow-up release.",
        color = appearance.textColor,
    )
    BottomSection(label = buttonLabel, enabled = true, onClick = { /* host handles dismiss */ })
}

@Composable
private fun localAppearanceSubmitLabel(): String = localAppearance().submitButtonText

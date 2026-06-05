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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.posthog.surveys.PostHogDisplaySurveyRatingType
import com.posthog.surveys.PostHogNextSurveyQuestion
import com.posthog.surveys.PostHogSurveyResponse
import kotlinx.coroutines.launch

/**
 * Top-level survey UI: a Material 3 [ModalBottomSheet] containing the active
 * question.
 *
 * Renders every supported question type (open text, single / multiple choice,
 * number / emoji rating, link) by dispatching on the concrete subtype of
 * [PostHogDisplaySurveyQuestion]. When the last question reports completion
 * and the customer has enabled the thank-you screen
 * ([com.posthog.android.surveys.compose.internal.theme.ResolvedSurveyAppearance.displayThankYouMessage]),
 * a [ConfirmationScreen] is shown before dismissal. Otherwise the sheet
 * dismisses immediately.
 *
 * Navigation honors the host SDK's branching: [onSubmit] returns a
 * [PostHogNextSurveyQuestion] describing the next question index and whether
 * the survey is complete. We advance to `next.questionIndex` rather than blindly
 * incrementing, so server-driven branching works. A `null` return is treated as
 * an abort and dismisses the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SurveySheet(
    survey: PostHogDisplaySurvey,
    onSurveyShown: () -> Unit,
    onSubmit: (questionIndex: Int, response: PostHogSurveyResponse) -> PostHogNextSurveyQuestion?,
    onClose: () -> Unit,
) {
    val appearance = remember(survey) { survey.appearance.resolve() }
    val coroutineScope = rememberCoroutineScope()

    // ModalBottomSheet is auto-presented; we intercept Hidden swipe-down so
    // dismissal only happens through the explicit X button.
    val sheetState =
        rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden },
        )

    var currentQuestionIndex by remember { mutableIntStateOf(0) }
    var showingConfirmation by remember { mutableStateOf(false) }
    val question = survey.questions.getOrNull(currentQuestionIndex)

    LaunchedEffect(survey.id) {
        onSurveyShown()
    }

    if (question == null && !showingConfirmation) {
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

    val onSubmitResponse: (PostHogSurveyResponse) -> Unit = { response ->
        // The host SDK computes the next step (server-driven branching) and
        // returns it; null means "abort".
        val next = onSubmit(currentQuestionIndex, response)
        when {
            next == null -> dismissSheet()
            next.isSurveyCompleted ->
                if (appearance.displayThankYouMessage) {
                    showingConfirmation = true
                } else {
                    dismissSheet()
                }
            else -> currentQuestionIndex = next.questionIndex
        }
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
                    if (showingConfirmation || question == null) {
                        ConfirmationScreen(onClose = dismissSheet)
                    } else {
                        QuestionContent(
                            question = question,
                            onSubmit = onSubmitResponse,
                        )
                    }
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
    onSubmit: (PostHogSurveyResponse) -> Unit,
) {
    when (question) {
        is PostHogDisplayRatingQuestion -> RatingQuestionDispatch(question, onSubmit)
        is PostHogDisplayOpenQuestion -> OpenTextQuestionDispatch(question, onSubmit)
        is PostHogDisplayLinkQuestion -> LinkQuestionDispatch(question, onSubmit)
        is PostHogDisplayChoiceQuestion ->
            if (question.isMultipleChoice) {
                MultipleChoiceQuestionDispatch(question, onSubmit)
            } else {
                SingleChoiceQuestionDispatch(question, onSubmit)
            }
        else -> UnsupportedQuestionPlaceholder(buttonLabel = question.buttonText ?: "Close")
    }
}

@Composable
private fun RatingQuestionDispatch(
    question: PostHogDisplayRatingQuestion,
    onSubmit: (PostHogSurveyResponse) -> Unit,
) {
    var rating by remember(question.id) { mutableStateOf<Int?>(null) }
    val canSubmit = question.isOptional || rating != null

    QuestionHeader(question)
    if (question.ratingType == PostHogDisplaySurveyRatingType.EMOJI) {
        EmojiRating(
            question = question,
            selectedValue = rating,
            onSelect = { rating = it },
        )
    } else {
        NumberRating(
            question = question,
            selectedValue = rating,
            onSelect = { rating = it },
        )
    }
    BottomSection(
        label = question.buttonText ?: localAppearance().submitButtonText,
        enabled = canSubmit,
        onClick = { onSubmit(PostHogSurveyResponse.Rating(rating)) },
    )
}

@Composable
private fun OpenTextQuestionDispatch(
    question: PostHogDisplayOpenQuestion,
    onSubmit: (PostHogSurveyResponse) -> Unit,
) {
    var text by remember(question.id) { mutableStateOf("") }
    val canSubmit = question.isOptional || text.isNotBlank()

    QuestionHeader(question)
    OpenText(question = question, value = text, onValueChange = { text = it })
    BottomSection(
        label = question.buttonText ?: localAppearance().submitButtonText,
        enabled = canSubmit,
        onClick = {
            val trimmed = text.trim()
            onSubmit(PostHogSurveyResponse.Text(if (trimmed.isEmpty()) null else text))
        },
    )
}

@Composable
private fun LinkQuestionDispatch(
    question: PostHogDisplayLinkQuestion,
    onSubmit: (PostHogSurveyResponse) -> Unit,
) {
    val context = LocalContext.current
    QuestionHeader(question)
    LinkQuestion(question = question)
    BottomSection(
        label = question.buttonText ?: localAppearance().submitButtonText,
        enabled = true,
        onClick = {
            openLink(context, question.link)
            onSubmit(PostHogSurveyResponse.Link(clicked = true))
        },
    )
}

@Composable
private fun SingleChoiceQuestionDispatch(
    question: PostHogDisplayChoiceQuestion,
    onSubmit: (PostHogSurveyResponse) -> Unit,
) {
    var selected by remember(question.id) { mutableStateOf<String?>(null) }
    var openInput by remember(question.id) { mutableStateOf("") }
    val openChoice = question.choices.lastOrNull()?.takeIf { question.hasOpenChoice }
    val hasOpenChoiceSelected = openChoice != null && selected == openChoice
    val canSubmit =
        question.isOptional ||
            (selected != null && (!hasOpenChoiceSelected || openInput.isNotBlank()))

    QuestionHeader(question)
    SingleChoice(
        question = question,
        selectedChoice = selected,
        onSelectedChoiceChange = { selected = it },
        openChoiceInput = openInput,
        onOpenChoiceInputChange = { openInput = it },
    )
    BottomSection(
        label = question.buttonText ?: localAppearance().submitButtonText,
        enabled = canSubmit,
        onClick = {
            val response = if (hasOpenChoiceSelected) openInput.trim() else selected
            onSubmit(PostHogSurveyResponse.SingleChoice(response))
        },
    )
}

@Composable
private fun MultipleChoiceQuestionDispatch(
    question: PostHogDisplayChoiceQuestion,
    onSubmit: (PostHogSurveyResponse) -> Unit,
) {
    var selected by remember(question.id) { mutableStateOf<Set<String>>(emptySet()) }
    var openInput by remember(question.id) { mutableStateOf("") }
    val openChoice = question.choices.lastOrNull()?.takeIf { question.hasOpenChoice }
    val hasOpenChoiceSelected = openChoice != null && openChoice in selected
    val canSubmit =
        question.isOptional ||
            (selected.isNotEmpty() && (!hasOpenChoiceSelected || openInput.isNotBlank()))

    QuestionHeader(question)
    MultipleChoice(
        question = question,
        selectedChoices = selected,
        onSelectedChoicesChange = { selected = it },
        openChoiceInput = openInput,
        onOpenChoiceInputChange = { openInput = it },
    )
    BottomSection(
        label = question.buttonText ?: localAppearance().submitButtonText,
        enabled = canSubmit,
        onClick = {
            val responses =
                selected.map { value ->
                    if (openChoice != null && value == openChoice) openInput.trim() else value
                }
            onSubmit(PostHogSurveyResponse.MultipleChoice(responses.ifEmpty { null }))
        },
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

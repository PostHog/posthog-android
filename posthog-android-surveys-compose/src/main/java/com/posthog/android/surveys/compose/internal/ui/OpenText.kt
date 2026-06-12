package com.posthog.android.surveys.compose.internal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.posthog.android.surveys.compose.internal.theme.LocalSurveyAppearance
import com.posthog.android.surveys.compose.internal.theme.localAppearance
import com.posthog.android.surveys.compose.internal.theme.resolve
import com.posthog.surveys.PostHogDisplayOpenQuestion
import com.posthog.surveys.PostHogDisplaySurveyAppearance
import com.posthog.surveys.PostHogDisplaySurveyTextContentType

/**
 * Multi-line text input for [PostHogDisplayOpenQuestion]s.
 *
 * State is hoisted — the composable receives the current [value] and emits
 * changes through [onValueChange]; deciding when a response is valid for
 * submission is the caller's responsibility (see `SurveySheet.kt`).
 *
 * A 150 dp fixed-height bordered card with a placeholder rendered behind the
 * input while empty.
 */
@Composable
internal fun OpenText(
    @Suppress("UnusedParameter") question: PostHogDisplayOpenQuestion,
    value: String,
    onValueChange: (String) -> Unit,
) {
    // `question` is part of the signature for symmetry with the other question types;
    // OpenText itself doesn't need any of its fields today (placeholder + appearance
    // come from the resolved appearance, validation lives in the dispatcher).
    val appearance = localAppearance()
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(appearance.inputBackgroundColor)
                .border(1.dp, appearance.borderColor, RoundedCornerShape(6.dp))
                .padding(8.dp),
    ) {
        if (value.isEmpty()) {
            Text(
                text = appearance.placeholder,
                color = appearance.placeholderTextColor,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxSize().restoreFocusOnReentry(),
            textStyle = LocalTextStyle.current.copy(color = appearance.inputTextColor),
            cursorBrush = SolidColor(appearance.inputTextColor),
        )
    }
}

private val previewOpenQuestion =
    PostHogDisplayOpenQuestion(
        id = "preview-open",
        question = "What can we do to improve our product?",
        questionDescription = "Any feedback will be helpful!",
        questionDescriptionContentType = PostHogDisplaySurveyTextContentType.TEXT,
        isOptional = false,
        buttonText = "Submit",
    )

@Preview(showBackground = true, widthDp = 360, name = "Default")
@Composable
private fun PreviewOpenTextDefault() {
    val appearance = remember { (null as PostHogDisplaySurveyAppearance?).resolve() }
    var text by remember { mutableStateOf("") }
    CompositionLocalProvider(LocalSurveyAppearance provides appearance) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(appearance.backgroundColor)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            QuestionHeader(previewOpenQuestion)
            OpenText(question = previewOpenQuestion, value = text, onValueChange = { text = it })
            BottomSection(label = "Submit", enabled = text.isNotBlank()) { }
        }
    }
}

@Preview(showBackground = true, widthDp = 360, name = "Themed")
@Composable
private fun PreviewOpenTextThemed() {
    val appearance =
        remember {
            PostHogDisplaySurveyAppearance(
                backgroundColor = "#FFE5B4",
                submitButtonColor = "#FF6B35",
                borderColor = "#F4C28C",
                placeholder = "Tell us more...",
                inputBackground = "#3A2A1A",
                inputTextColor = "#FFF3E0",
            ).resolve()
        }
    var text by remember { mutableStateOf("This is a sample response.") }
    CompositionLocalProvider(LocalSurveyAppearance provides appearance) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(appearance.backgroundColor)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            QuestionHeader(previewOpenQuestion)
            OpenText(question = previewOpenQuestion, value = text, onValueChange = { text = it })
            BottomSection(label = "Send", enabled = text.isNotBlank()) { }
        }
    }
}

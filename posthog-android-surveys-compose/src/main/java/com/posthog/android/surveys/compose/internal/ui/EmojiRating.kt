@file:Suppress("MagicNumber", "LongMethod")

package com.posthog.android.surveys.compose.internal.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.posthog.android.surveys.compose.internal.theme.LocalSurveyAppearance
import com.posthog.android.surveys.compose.internal.theme.localAppearance
import com.posthog.android.surveys.compose.internal.theme.resolve
import com.posthog.surveys.PostHogDisplayRatingQuestion
import com.posthog.surveys.PostHogDisplaySurveyAppearance
import com.posthog.surveys.PostHogDisplaySurveyRatingType
import com.posthog.surveys.PostHogDisplaySurveyTextContentType

/**
 * Emoji rating control for [PostHogDisplayRatingQuestion]s with
 * [PostHogDisplayRatingQuestion.ratingType] equal to
 * [PostHogDisplaySurveyRatingType.EMOJI].
 *
 * Renders 3 or 5 face shapes (Dissatisfied/Neutral/Satisfied for scale 3,
 * VeryDissatisfied → VerySatisfied for scale 5) as a 1:1 port of the iOS
 * shapes in `Resources.swift`. Same SVG paths, same normalised
 * coordinate space, same fill mode — selected emojis are tinted
 * [com.posthog.android.surveys.compose.internal.theme.ResolvedSurveyAppearance.ratingButtonActiveColor],
 * unselected use `ratingButtonColor`.
 */
@Composable
internal fun EmojiRating(
    question: PostHogDisplayRatingQuestion,
    selectedValue: Int?,
    onSelect: (Int?) -> Unit,
) {
    val appearance = localAppearance()
    val emojis = emojisForScale(question.scaleUpperBound - question.scaleLowerBound + 1)
    val values = (1..emojis.size).toList()

    Column {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            values.forEachIndexed { index, value ->
                val isSelected = selectedValue == value
                val tint =
                    if (isSelected) appearance.ratingButtonActiveColor else appearance.ratingButtonColor
                Box(
                    modifier =
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable {
                                onSelect(if (isSelected) null else value)
                            },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(modifier = Modifier.size(48.dp)) {
                        val w = size.width
                        val h = size.height
                        drawPath(emojis[index].buildPath(w, h), color = tint)
                    }
                }
                if (index != values.lastIndex) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }

        val lower = question.lowerBoundLabel
        val upper = question.upperBoundLabel
        if (lower.isNotBlank() || upper.isNotBlank()) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(text = lower, color = appearance.descriptionTextColor)
                Spacer(modifier = Modifier.weight(1f))
                Text(text = upper, color = appearance.descriptionTextColor)
            }
        }
    }
}

private fun emojisForScale(scale: Int): List<PostHogEmoji> =
    when (scale) {
        3 -> listOf(PostHogEmoji.Dissatisfied, PostHogEmoji.Neutral, PostHogEmoji.Satisfied)
        else ->
            listOf(
                PostHogEmoji.VeryDissatisfied,
                PostHogEmoji.Dissatisfied,
                PostHogEmoji.Neutral,
                PostHogEmoji.Satisfied,
                PostHogEmoji.VerySatisfied,
            )
    }

private enum class PostHogEmoji {
    VeryDissatisfied,
    Dissatisfied,
    Neutral,
    Satisfied,
    VerySatisfied,
    ;

    fun buildPath(
        w: Float,
        h: Float,
    ): Path {
        val path = Path().apply { fillType = PathFillType.EvenOdd }
        when (this) {
            VeryDissatisfied -> path.veryDissatisfied(w, h)
            Dissatisfied -> path.dissatisfied(w, h)
            Neutral -> path.neutral(w, h)
            Satisfied -> path.satisfied(w, h)
            VerySatisfied -> path.verySatisfied(w, h)
        }
        // Mirrors iOS `path.offsetBy(dx: 0, dy: height)` — the original SVG paths use
        // y values in [-1, 0] so the trailing translate shifts them into the [0, 1]
        // drawing rect.
        path.translate(Offset(0f, h))
        return path
    }
}

// region path helpers

private fun Path.quad(
    toX: Float,
    toY: Float,
    ctrlX: Float,
    ctrlY: Float,
) {
    // Compose's argument order is (control, end); iOS's is (end, control). Wrapping
    // the call lets us keep iOS's parameter order in the call-sites for easier review.
    quadraticBezierTo(ctrlX, ctrlY, toX, toY)
}

private fun Path.addFaceCircle(
    w: Float,
    h: Float,
) {
    // Outer circle
    moveTo(0.5f * w, -0.08333f * h)
    quad(0.3375f * w, -0.11615f * h, 0.41354f * w, -0.08333f * h)
    quad(0.20521f * w, -0.20521f * h, 0.26146f * w, -0.14896f * h)
    quad(0.11615f * w, -0.3375f * h, 0.14896f * w, -0.26146f * h)
    quad(0.08333f * w, -0.5f * h, 0.08333f * w, -0.41354f * h)
    quad(0.11615f * w, -0.6625f * h, 0.08333f * w, -0.58646f * h)
    quad(0.20521f * w, -0.79479f * h, 0.14896f * w, -0.73854f * h)
    quad(0.3375f * w, -0.88385f * h, 0.26146f * w, -0.85104f * h)
    quad(0.5f * w, -0.91667f * h, 0.41354f * w, -0.91667f * h)
    quad(0.6625f * w, -0.88385f * h, 0.58646f * w, -0.91667f * h)
    quad(0.79479f * w, -0.79479f * h, 0.73854f * w, -0.85104f * h)
    quad(0.88385f * w, -0.6625f * h, 0.85104f * w, -0.73854f * h)
    quad(0.91667f * w, -0.5f * h, 0.91667f * w, -0.58646f * h)
    quad(0.88385f * w, -0.3375f * h, 0.91667f * w, -0.41354f * h)
    quad(0.79479f * w, -0.20521f * h, 0.85104f * w, -0.26146f * h)
    quad(0.6625f * w, -0.11615f * h, 0.73854f * w, -0.14896f * h)
    quad(0.5f * w, -0.08333f * h, 0.58646f * w, -0.08333f * h)
    close()
    // Inner-stroke (EvenOdd fill mode means the area between these two ovals becomes
    // the visible ring).
    moveTo(0.5f * w, -0.5f * h)
    close()
    moveTo(0.5f * w, -0.14583f * h)
    quad(0.75124f * w, -0.24876f * h, 0.64831f * w, -0.14583f * h)
    quad(0.85417f * w, -0.5f * h, 0.85417f * w, -0.35169f * h)
    quad(0.75124f * w, -0.75124f * h, 0.85417f * w, -0.64831f * h)
    quad(0.5f * w, -0.85417f * h, 0.64831f * w, -0.85417f * h)
    quad(0.24876f * w, -0.75124f * h, 0.35169f * w, -0.85417f * h)
    quad(0.14583f * w, -0.5f * h, 0.14583f * w, -0.64831f * h)
    quad(0.24876f * w, -0.24876f * h, 0.14583f * w, -0.35169f * h)
    quad(0.5f * w, -0.14583f * h, 0.35169f * w, -0.14583f * h)
    close()
}

private fun Path.addRightDotEye(
    w: Float,
    h: Float,
) {
    moveTo(0.65208f * w, -0.55521f * h)
    quad(0.69193f * w, -0.57161f * h, 0.67552f * w, -0.55521f * h)
    quad(0.70833f * w, -0.61146f * h, 0.70833f * w, -0.58802f * h)
    quad(0.69193f * w, -0.6513f * h, 0.70833f * w, -0.6349f * h)
    quad(0.65208f * w, -0.66771f * h, 0.67552f * w, -0.66771f * h)
    quad(0.61224f * w, -0.6513f * h, 0.62865f * w, -0.66771f * h)
    quad(0.59583f * w, -0.61146f * h, 0.59583f * w, -0.6349f * h)
    quad(0.61224f * w, -0.57161f * h, 0.59583f * w, -0.58802f * h)
    quad(0.65208f * w, -0.55521f * h, 0.62865f * w, -0.55521f * h)
    close()
}

private fun Path.addLeftDotEye(
    w: Float,
    h: Float,
) {
    moveTo(0.34792f * w, -0.55521f * h)
    quad(0.38776f * w, -0.57161f * h, 0.37135f * w, -0.55521f * h)
    quad(0.40417f * w, -0.61146f * h, 0.40417f * w, -0.58802f * h)
    quad(0.38776f * w, -0.6513f * h, 0.40417f * w, -0.6349f * h)
    quad(0.34792f * w, -0.66771f * h, 0.37135f * w, -0.66771f * h)
    quad(0.30807f * w, -0.6513f * h, 0.32448f * w, -0.66771f * h)
    quad(0.29167f * w, -0.61146f * h, 0.29167f * w, -0.6349f * h)
    quad(0.30807f * w, -0.57161f * h, 0.29167f * w, -0.58802f * h)
    quad(0.34792f * w, -0.55521f * h, 0.32448f * w, -0.55521f * h)
    close()
}

// endregion

// region per-emoji paths

private fun Path.veryDissatisfied(
    w: Float,
    h: Float,
) {
    // Frown mouth
    moveTo(0.5f * w, -0.43438f * h)
    quad(0.37344f * w, -0.39531f * h, 0.43021f * w, -0.43438f * h)
    quad(0.28958f * w, -0.29167f * h, 0.31667f * w, -0.35625f * h)
    lineTo(0.71042f * w, -0.29167f * h)
    quad(0.62708f * w, -0.39583f * h, 0.68437f * w, -0.35729f * h)
    quad(0.5f * w, -0.43438f * h, 0.56979f * w, -0.43438f * h)
    close()
    // Left X-eye
    moveTo(0.30938f * w, -0.50938f * h)
    lineTo(0.36146f * w, -0.55625f * h)
    lineTo(0.40833f * w, -0.50938f * h)
    lineTo(0.44062f * w, -0.54688f * h)
    lineTo(0.39375f * w, -0.59375f * h)
    lineTo(0.44062f * w, -0.64063f * h)
    lineTo(0.40833f * w, -0.67812f * h)
    lineTo(0.36146f * w, -0.63125f * h)
    lineTo(0.30938f * w, -0.67812f * h)
    lineTo(0.27708f * w, -0.64063f * h)
    lineTo(0.32396f * w, -0.59375f * h)
    lineTo(0.27708f * w, -0.54688f * h)
    lineTo(0.30938f * w, -0.50938f * h)
    close()
    // Right X-eye
    moveTo(0.59271f * w, -0.50938f * h)
    lineTo(0.63854f * w, -0.55625f * h)
    lineTo(0.69167f * w, -0.50938f * h)
    lineTo(0.72396f * w, -0.54688f * h)
    lineTo(0.67708f * w, -0.59375f * h)
    lineTo(0.72396f * w, -0.64063f * h)
    lineTo(0.69167f * w, -0.67812f * h)
    lineTo(0.63854f * w, -0.63125f * h)
    lineTo(0.59271f * w, -0.67812f * h)
    lineTo(0.56042f * w, -0.64063f * h)
    lineTo(0.60625f * w, -0.59375f * h)
    lineTo(0.56042f * w, -0.54688f * h)
    lineTo(0.59271f * w, -0.50938f * h)
    close()
    addFaceCircle(w, h)
}

private fun Path.dissatisfied(
    w: Float,
    h: Float,
) {
    addRightDotEye(w, h)
    addLeftDotEye(w, h)
    // Frown mouth (slightly different from VeryDissatisfied — it has an inner arc)
    moveTo(0.50018f * w, -0.43438f * h)
    quad(0.37344f * w, -0.39531f * h, 0.43021f * w, -0.43438f * h)
    quad(0.28958f * w, -0.29167f * h, 0.31667f * w, -0.35625f * h)
    lineTo(0.34479f * w, -0.29167f * h)
    quad(0.40956f * w, -0.35938f * h, 0.36771f * w, -0.33542f * h)
    quad(0.5007f * w, -0.38333f * h, 0.4514f * w, -0.38333f * h)
    quad(0.59115f * w, -0.35885f * h, 0.55f * w, -0.38333f * h)
    quad(0.65625f * w, -0.29167f * h, 0.63229f * w, -0.33437f * h)
    lineTo(0.71042f * w, -0.29167f * h)
    quad(0.62726f * w, -0.39583f * h, 0.68437f * w, -0.35729f * h)
    quad(0.50018f * w, -0.43438f * h, 0.57015f * w, -0.43438f * h)
    close()
    addFaceCircle(w, h)
}

private fun Path.neutral(
    w: Float,
    h: Float,
) {
    addRightDotEye(w, h)
    addLeftDotEye(w, h)
    // Flat mouth (rectangle drawn with line segments)
    moveTo(0.36875f * w, -0.35313f * h)
    lineTo(0.63229f * w, -0.35313f * h)
    lineTo(0.63229f * w, -0.40417f * h)
    lineTo(0.36875f * w, -0.40417f * h)
    lineTo(0.36875f * w, -0.35313f * h)
    close()
    addFaceCircle(w, h)
}

private fun Path.satisfied(
    w: Float,
    h: Float,
) {
    addRightDotEye(w, h)
    addLeftDotEye(w, h)
    // Smile mouth
    moveTo(0.5f * w, -0.27187f * h)
    quad(0.62656f * w, -0.30885f * h, 0.56875f * w, -0.27187f * h)
    quad(0.71042f * w, -0.40937f * h, 0.68437f * w, -0.34583f * h)
    lineTo(0.65625f * w, -0.40937f * h)
    quad(0.59062f * w, -0.34531f * h, 0.63229f * w, -0.36771f * h)
    quad(0.50052f * w, -0.32292f * h, 0.54896f * w, -0.32292f * h)
    quad(0.4099f * w, -0.34479f * h, 0.45208f * w, -0.32292f * h)
    quad(0.34479f * w, -0.40937f * h, 0.36771f * w, -0.36667f * h)
    lineTo(0.28958f * w, -0.40937f * h)
    quad(0.37396f * w, -0.30885f * h, 0.31667f * w, -0.34583f * h)
    quad(0.5f * w, -0.27187f * h, 0.43125f * w, -0.27187f * h)
    close()
    addFaceCircle(w, h)
}

private fun Path.verySatisfied(
    w: Float,
    h: Float,
) {
    // Wide smile
    moveTo(0.49948f * w, -0.27187f * h)
    quad(0.6099f * w, -0.29896f * h, 0.55937f * w, -0.27187f * h)
    quad(0.69167f * w, -0.37437f * h, 0.66042f * w, -0.32604f * h)
    quad(0.69089f * w, -0.39792f * h, 0.69792f * w, -0.38646f * h)
    quad(0.66979f * w, -0.40937f * h, 0.68385f * w, -0.40937f * h)
    lineTo(0.33012f * w, -0.40937f * h)
    quad(0.30885f * w, -0.39792f * h, 0.31562f * w, -0.40937f * h)
    quad(0.30833f * w, -0.37437f * h, 0.30208f * w, -0.38646f * h)
    quad(0.3901f * w, -0.29896f * h, 0.33958f * w, -0.32604f * h)
    quad(0.49948f * w, -0.27187f * h, 0.44062f * w, -0.27187f * h)
    close()
    // Left "smile-line" eye
    moveTo(0.36146f * w, -0.60208f * h)
    lineTo(0.38958f * w, -0.57396f * h)
    quad(0.40814f * w, -0.56563f * h, 0.39754f * w, -0.56563f * h)
    quad(0.42708f * w, -0.57396f * h, 0.41875f * w, -0.56563f * h)
    quad(0.43542f * w, -0.59271f * h, 0.43542f * w, -0.58229f * h)
    quad(0.42708f * w, -0.61146f * h, 0.43542f * w, -0.60313f * h)
    lineTo(0.38333f * w, -0.65521f * h)
    quad(0.36156f * w, -0.66458f * h, 0.37417f * w, -0.66458f * h)
    quad(0.33958f * w, -0.65521f * h, 0.34896f * w, -0.66458f * h)
    lineTo(0.29583f * w, -0.61146f * h)
    quad(0.2875f * w, -0.5929f * h, 0.2875f * w, -0.6035f * h)
    quad(0.29583f * w, -0.57396f * h, 0.2875f * w, -0.58229f * h)
    quad(0.31458f * w, -0.56563f * h, 0.30417f * w, -0.56563f * h)
    quad(0.33333f * w, -0.57396f * h, 0.325f * w, -0.56563f * h)
    lineTo(0.36146f * w, -0.60208f * h)
    close()
    // Right "smile-line" eye
    moveTo(0.63958f * w, -0.60208f * h)
    lineTo(0.66771f * w, -0.57396f * h)
    quad(0.68646f * w, -0.56563f * h, 0.67574f * w, -0.56563f * h)
    quad(0.70521f * w, -0.57396f * h, 0.69717f * w, -0.56563f * h)
    quad(0.71354f * w, -0.59252f * h, 0.71354f * w, -0.58191f * h)
    quad(0.70521f * w, -0.61146f * h, 0.71354f * w, -0.60313f * h)
    lineTo(0.66146f * w, -0.65521f * h)
    quad(0.63969f * w, -0.66458f * h, 0.65229f * w, -0.66458f * h)
    quad(0.61771f * w, -0.65521f * h, 0.62708f * w, -0.66458f * h)
    lineTo(0.57396f * w, -0.61146f * h)
    quad(0.56563f * w, -0.59271f * h, 0.56563f * w, -0.60342f * h)
    quad(0.57396f * w, -0.57396f * h, 0.56563f * w, -0.58199f * h)
    quad(0.59252f * w, -0.56563f * h, 0.58191f * w, -0.56563f * h)
    quad(0.61146f * w, -0.57396f * h, 0.60313f * w, -0.56563f * h)
    lineTo(0.63958f * w, -0.60208f * h)
    close()
    addFaceCircle(w, h)
}

// endregion

private val previewEmojiQuestion =
    PostHogDisplayRatingQuestion(
        id = "preview-emoji",
        question = "How was your experience?",
        questionDescription = null,
        questionDescriptionContentType = PostHogDisplaySurveyTextContentType.TEXT,
        isOptional = false,
        buttonText = "Submit",
        ratingType = PostHogDisplaySurveyRatingType.EMOJI,
        scaleLowerBound = 1,
        scaleUpperBound = 5,
        lowerBoundLabel = "Awful",
        upperBoundLabel = "Amazing",
    )

@Preview(showBackground = true, widthDp = 360, name = "Default 5-emoji")
@Composable
private fun PreviewEmojiRatingDefault() {
    val appearance = remember { (null as PostHogDisplaySurveyAppearance?).resolve() }
    var rating by remember { mutableStateOf<Int?>(null) }
    CompositionLocalProvider(LocalSurveyAppearance provides appearance) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(appearance.backgroundColor)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            QuestionHeader(previewEmojiQuestion)
            EmojiRating(
                question = previewEmojiQuestion,
                selectedValue = rating,
                onSelect = { rating = it },
            )
            BottomSection(label = "Submit", enabled = rating != null) { }
        }
    }
}

@Preview(showBackground = true, widthDp = 360, name = "Themed 3-emoji")
@Composable
private fun PreviewEmojiRatingThemed() {
    val appearance =
        remember {
            PostHogDisplaySurveyAppearance(
                backgroundColor = "#FFE5B4",
                submitButtonColor = "#FF6B35",
                ratingButtonColor = "#F4C28C",
                ratingButtonActiveColor = "#FF6B35",
            ).resolve()
        }
    val threeEmojiQuestion =
        remember {
            PostHogDisplayRatingQuestion(
                id = "preview-3",
                question = "How easy was it?",
                questionDescription = null,
                questionDescriptionContentType = PostHogDisplaySurveyTextContentType.TEXT,
                isOptional = false,
                buttonText = "Submit",
                ratingType = PostHogDisplaySurveyRatingType.EMOJI,
                scaleLowerBound = 1,
                scaleUpperBound = 3,
                lowerBoundLabel = "Hard",
                upperBoundLabel = "Easy",
            )
        }
    var rating by remember { mutableStateOf<Int?>(2) }
    CompositionLocalProvider(LocalSurveyAppearance provides appearance) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(appearance.backgroundColor)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            QuestionHeader(threeEmojiQuestion)
            EmojiRating(
                question = threeEmojiQuestion,
                selectedValue = rating,
                onSelect = { rating = it },
            )
            BottomSection(label = "Send", enabled = rating != null) { }
        }
    }
}

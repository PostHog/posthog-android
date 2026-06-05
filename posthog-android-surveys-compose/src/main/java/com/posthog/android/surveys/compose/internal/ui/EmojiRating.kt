@file:Suppress("MagicNumber")

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
import androidx.compose.ui.graphics.Path
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
 * Renders 3 or 5 face shapes (Dissatisfied / Neutral / Satisfied for scale 3,
 * VeryDissatisfied → VerySatisfied for scale 5). The face artwork is the same
 * SVG used by the web product (posthog-js
 * `packages/browser/src/extensions/surveys/icons.tsx`) — the exact `d` path
 * strings are embedded verbatim in [PostHogEmoji] and parsed at draw time by
 * `parseSvgPath`. Unicode emoji is deliberately avoided because OEM rendering
 * varies dramatically across Android devices.
 *
 * Selected emojis are tinted
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
    // Thumbs up/down (a 2-point emoji scale) hides the bound labels — thumbs
    // don't need "lower/upper" captions.
    val showBoundLabels = emojis.size > 2

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
                    Canvas(modifier = Modifier.size(40.dp)) {
                        drawPath(emojis[index].buildPath(size.width, size.height), color = tint)
                    }
                }
                if (index != values.lastIndex) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }

        val lower = question.lowerBoundLabel
        val upper = question.upperBoundLabel
        if (showBoundLabels && (lower.isNotBlank() || upper.isNotBlank())) {
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
        2 -> listOf(PostHogEmoji.ThumbsUp, PostHogEmoji.ThumbsDown)
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

/**
 * The emoji glyphs used by the rating control, each carrying the verbatim SVG
 * `d` path string from posthog-js.
 *
 * The five faces share the Material-Symbols viewBox `0 -960 960 960` (x in
 * `[0, 960]`, y in `[-960, 0]`), so they pass `viewBox = 960` and `viewBoxY = 960`
 * to shift the negative y-range into the draw rect. The thumbs glyphs use the
 * standard `0 0 24 24` viewBox (`viewBox = 24`, `viewBoxY = 0`). [buildPath]
 * scales `size / viewBox` accordingly.
 */
private enum class PostHogEmoji(
    val svgPath: String,
    val viewBox: Float = 960f,
    val viewBoxY: Float = 960f,
) {
    // https://github.com/PostHog/posthog-js — packages/browser/src/extensions/surveys/icons.tsx
    VeryDissatisfied(
        "M480-417q-67 0-121.5 37.5T278-280h404q-25-63-80-100t-122-37Zm-183-72 50-45 45 45 31-36-45-45 " +
            "45-45-31-36-45 45-50-45-31 36 45 45-45 45 31 36Zm272 0 44-45 51 45 31-36-45-45 45-45-31-36-51 " +
            "45-44-45-31 36 44 45-44 45 31 36ZM480-80q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 " +
            "31.5-156T197-763q54-54 127-85.5T480-880q83 0 156 31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 " +
            "156T763-197q-54 54-127 85.5T480-80Zm0-400Zm0 340q142 0 241-99t99-241q0-142-99-241t-241-99q-142 " +
            "0-241 99t-99 241q0 142 99 241t241 99Z",
    ),
    Dissatisfied(
        "M626-533q22.5 0 38.25-15.75T680-587q0-22.5-15.75-38.25T626-641q-22.5 0-38.25 15.75T572-587q0 22.5 " +
            "15.75 38.25T626-533Zm-292 0q22.5 0 38.25-15.75T388-587q0-22.5-15.75-38.25T334-641q-22.5 0-38.25 " +
            "15.75T280-587q0 22.5 15.75 38.25T334-533Zm146.174 116Q413-417 358.5-379.5T278-280h53q22-42 " +
            "62.173-65t87.5-23Q528-368 567.5-344.5T630-280h52q-25-63-79.826-100-54.826-37-122-37ZM480-80q-83 " +
            "0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 31.5-156T197-763q54-54 127-85.5T480-880q83 0 156 " +
            "31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 156T763-197q-54 54-127 85.5T480-80Zm0-400Zm0 " +
            "340q142.375 0 241.188-98.812Q820-337.625 820-480t-98.812-241.188Q622.375-820 480-820t-241.188 " +
            "98.812Q140-622.375 140-480t98.812 241.188Q337.625-140 480-140Z",
    ),
    Neutral(
        "M626-533q22.5 0 38.25-15.75T680-587q0-22.5-15.75-38.25T626-641q-22.5 0-38.25 15.75T572-587q0 22.5 " +
            "15.75 38.25T626-533Zm-292 0q22.5 0 38.25-15.75T388-587q0-22.5-15.75-38.25T334-641q-22.5 0-38.25 " +
            "15.75T280-587q0 22.5 15.75 38.25T334-533Zm20 194h253v-49H354v49ZM480-80q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 " +
            "31.5-156T197-763q54-54 127-85.5T480-880q83 0 156 31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 " +
            "156T763-197q-54 54-127 85.5T480-80Zm0-400Zm0 340q142.375 0 241.188-98.812Q820-337.625 " +
            "820-480t-98.812-241.188Q622.375-820 480-820t-241.188 98.812Q140-622.375 140-480t98.812 " +
            "241.188Q337.625-140 480-140Z",
    ),
    Satisfied(
        "M626-533q22.5 0 38.25-15.75T680-587q0-22.5-15.75-38.25T626-641q-22.5 0-38.25 15.75T572-587q0 22.5 " +
            "15.75 38.25T626-533Zm-292 0q22.5 0 38.25-15.75T388-587q0-22.5-15.75-38.25T334-641q-22.5 0-38.25 " +
            "15.75T280-587q0 22.5 15.75 38.25T334-533Zm146 272q66 0 121.5-35.5T682-393h-52q-23 40-63 " +
            "61.5T480.5-310q-46.5 0-87-21T331-393h-53q26 61 81 96.5T480-261Zm0 181q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 " +
            "31.5-156T197-763q54-54 127-85.5T480-880q83 0 156 31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 " +
            "156T763-197q-54 54-127 85.5T480-80Zm0-400Zm0 340q142.375 0 241.188-98.812Q820-337.625 " +
            "820-480t-98.812-241.188Q622.375-820 480-820t-241.188 98.812Q140-622.375 140-480t98.812 " +
            "241.188Q337.625-140 480-140Z",
    ),
    VerySatisfied(
        "M479.504-261Q537-261 585.5-287q48.5-26 78.5-72.4 6-11.6-.75-22.6-6.75-11-20.25-11H316.918Q303-393 " +
            "296.5-382t-.5 22.6q30 46.4 78.5 72.4 48.5 26 105.004 26ZM347-578l27 27q7.636 8 17.818 8Q402-543 " +
            "410-551q8-8 8-18t-8-18l-42-42q-8.8-9-20.9-9-12.1 0-21.1 9l-42 42q-8 7.636-8 17.818Q276-559 " +
            "284-551q8 8 18 8t18-8l27-27Zm267 0 27 27q7.714 8 18 8t18-8q8-7.636 8-17.818Q685-579 677-587l-42-42q-8.8-9-20.9-9-12.1 " +
            "0-21.1 9l-42 42q-8 7.714-8 18t8 18q7.636 8 17.818 8Q579-543 587-551l27-27ZM480-80q-83 " +
            "0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 " +
            "31.5-156T197-763q54-54 127-85.5T480-880q83 0 156 31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 " +
            "156T763-197q-54 54-127 85.5T480-80Zm0-400Zm0 340q142.375 0 241.188-98.812Q820-337.625 " +
            "820-480t-98.812-241.188Q622.375-820 480-820t-241.188 98.812Q140-622.375 140-480t98.812 " +
            "241.188Q337.625-140 480-140Z",
    ),
    ThumbsUp(
        "M2 20h2c.55 0 1-.45 1-1v-9c0-.55-.45-1-1-1H2v11zm19.83-7.12c.11-.25.17-.52.17-.8V11c0-1.1-.9-2-2-2h-5.5l" +
            ".92-4.65c.05-.22.02-.46-.08-.66-.23-.45-.52-.86-.88-1.22L14 2 7.59 8.41C7.21 8.79 7 9.3 7 9.83v7.84C7 " +
            "18.95 8.05 20 9.34 20h8.11c.7 0 1.36-.37 1.72-.97l2.66-6.15z",
        viewBox = 24f,
        viewBoxY = 0f,
    ),
    ThumbsDown(
        "M22 4h-2c-.55 0-1 .45-1 1v9c0 .55.45 1 1 1h2V4zM2.17 11.12c-.11.25-.17.52-.17.8V13c0 1.1.9 2 2 2h5.5l-.92 " +
            "4.65c-.05.22-.02.46.08.66.23.45.52.86.88 1.22L10 22l6.41-6.41c.38-.38.59-.89.59-1.42V6.34C17 5.05 " +
            "15.95 4 14.66 4H6.55c-.7 0-1.36.37-1.72.97l-2.66 6.15z",
        viewBox = 24f,
        viewBoxY = 0f,
    ),
    ;

    /**
     * Parses the SVG path and scales it from the glyph's [viewBox] into a
     * `[0, w] x [0, h]` rect. For the faces (viewBox `0 -960 960 960`) the
     * y-axis is shifted up by one viewbox height so the negative SVG y-range
     * lands inside the rect; thumbs (`0 0 24 24`) use no shift.
     */
    fun buildPath(
        w: Float,
        h: Float,
    ): Path = parseSvgPath(svgPath, scaleX = w / viewBox, scaleY = h / viewBox, translateY = viewBoxY)
}

/**
 * Minimal SVG path-data parser sufficient for the Material-Symbols emoji glyphs
 * above. Supports the commands those glyphs use: M/m, L/l, H/h, V/v, Q/q, T/t,
 * Z/z (and tolerates C/c, S/s for safety). Coordinates are transformed by
 * `(x*scaleX, (y+translateY)*scaleY)` so the caller can map the SVG viewBox into
 * the Compose drawing rect.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth")
private fun parseSvgPath(
    d: String,
    scaleX: Float,
    scaleY: Float,
    translateY: Float,
): Path {
    val path = Path()
    val tokens = tokenizeSvgPath(d)
    var i = 0

    // Current point and sub-path start, in SVG user units.
    var curX = 0f
    var curY = 0f
    var startX = 0f
    var startY = 0f
    // Reflection point for smooth quad (T/t).
    var lastCtrlX = 0f
    var lastCtrlY = 0f
    var prevWasQuad = false

    fun tx(x: Float) = x * scaleX

    fun ty(y: Float) = (y + translateY) * scaleY

    fun nextNum(): Float = tokens[i++].toFloat()

    var command = ' '
    while (i < tokens.size) {
        val token = tokens[i]
        command =
            if (token.length == 1 && token[0].isLetter()) {
                i++
                token[0]
            } else {
                // Implicit repeat of the previous command (e.g. repeated L after L).
                command
            }

        val relative = command.isLowerCase()
        when (command.uppercaseChar()) {
            'M' -> {
                val x = (if (relative) curX else 0f) + nextNum()
                val y = (if (relative) curY else 0f) + nextNum()
                curX = x
                curY = y
                startX = x
                startY = y
                path.moveTo(tx(x), ty(y))
                // Subsequent implicit pairs after M are treated as L (SVG spec).
                command = if (relative) 'l' else 'L'
                prevWasQuad = false
            }
            'L' -> {
                val x = (if (relative) curX else 0f) + nextNum()
                val y = (if (relative) curY else 0f) + nextNum()
                curX = x
                curY = y
                path.lineTo(tx(x), ty(y))
                prevWasQuad = false
            }
            'H' -> {
                val x = (if (relative) curX else 0f) + nextNum()
                curX = x
                path.lineTo(tx(x), ty(curY))
                prevWasQuad = false
            }
            'V' -> {
                val y = (if (relative) curY else 0f) + nextNum()
                curY = y
                path.lineTo(tx(curX), ty(y))
                prevWasQuad = false
            }
            'Q' -> {
                val cx = (if (relative) curX else 0f) + nextNum()
                val cy = (if (relative) curY else 0f) + nextNum()
                val x = (if (relative) curX else 0f) + nextNum()
                val y = (if (relative) curY else 0f) + nextNum()
                path.quadraticTo(tx(cx), ty(cy), tx(x), ty(y))
                lastCtrlX = cx
                lastCtrlY = cy
                curX = x
                curY = y
                prevWasQuad = true
            }
            'T' -> {
                val cx = if (prevWasQuad) 2 * curX - lastCtrlX else curX
                val cy = if (prevWasQuad) 2 * curY - lastCtrlY else curY
                val x = (if (relative) curX else 0f) + nextNum()
                val y = (if (relative) curY else 0f) + nextNum()
                path.quadraticTo(tx(cx), ty(cy), tx(x), ty(y))
                lastCtrlX = cx
                lastCtrlY = cy
                curX = x
                curY = y
                prevWasQuad = true
            }
            'C' -> {
                val c1x = (if (relative) curX else 0f) + nextNum()
                val c1y = (if (relative) curY else 0f) + nextNum()
                val c2x = (if (relative) curX else 0f) + nextNum()
                val c2y = (if (relative) curY else 0f) + nextNum()
                val x = (if (relative) curX else 0f) + nextNum()
                val y = (if (relative) curY else 0f) + nextNum()
                path.cubicTo(tx(c1x), ty(c1y), tx(c2x), ty(c2y), tx(x), ty(y))
                curX = x
                curY = y
                prevWasQuad = false
            }
            'Z' -> {
                path.close()
                curX = startX
                curY = startY
                prevWasQuad = false
            }
            else -> i++ // unknown command — skip defensively
        }
    }
    return path
}

/**
 * Splits an SVG `d` string into command letters and numbers. Handles signs as
 * number delimiters (`-` and `+`), the implicit `+`/`-` after exponent or
 * decimal, and the fact that a second `.` starts a new number (e.g. `.5.5`).
 */
private fun tokenizeSvgPath(d: String): List<String> {
    val tokens = mutableListOf<String>()
    val number = StringBuilder()
    var seenDot = false

    fun flush() {
        if (number.isNotEmpty()) {
            tokens.add(number.toString())
            number.clear()
            seenDot = false
        }
    }

    for (c in d) {
        when {
            c.isLetter() -> {
                flush()
                tokens.add(c.toString())
            }
            c == '-' || c == '+' -> {
                // A sign starts a new number unless it directly follows an exponent.
                val prev = number.lastOrNull()
                if (prev == 'e' || prev == 'E') {
                    number.append(c)
                } else {
                    flush()
                    number.append(c)
                }
            }
            c == '.' -> {
                if (seenDot) {
                    flush()
                }
                seenDot = true
                number.append(c)
            }
            c == ',' || c.isWhitespace() -> flush()
            else -> number.append(c)
        }
    }
    flush()
    return tokens
}

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

@Preview(showBackground = true, widthDp = 360, name = "Thumbs up/down")
@Composable
private fun PreviewEmojiRatingThumbs() {
    val appearance = remember { (null as PostHogDisplaySurveyAppearance?).resolve() }
    val thumbsQuestion =
        remember {
            PostHogDisplayRatingQuestion(
                id = "preview-thumbs",
                question = "Did this answer your question?",
                questionDescription = null,
                questionDescriptionContentType = PostHogDisplaySurveyTextContentType.TEXT,
                isOptional = false,
                buttonText = "Submit",
                ratingType = PostHogDisplaySurveyRatingType.EMOJI,
                scaleLowerBound = 1,
                scaleUpperBound = 2,
                lowerBoundLabel = "",
                upperBoundLabel = "",
            )
        }
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
            QuestionHeader(thumbsQuestion)
            EmojiRating(
                question = thumbsQuestion,
                selectedValue = rating,
                onSelect = { rating = it },
            )
            BottomSection(label = "Submit", enabled = rating != null) { }
        }
    }
}

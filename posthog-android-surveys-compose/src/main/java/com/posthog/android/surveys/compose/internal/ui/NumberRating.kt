package com.posthog.android.surveys.compose.internal.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.posthog.android.surveys.compose.internal.theme.contrastingTextColor
import com.posthog.android.surveys.compose.internal.theme.localAppearance
import com.posthog.surveys.PostHogDisplayRatingQuestion

@Composable
internal fun NumberRating(
    question: PostHogDisplayRatingQuestion,
    selectedValue: Int?,
    onSelect: (Int?) -> Unit,
) {
    val appearance = localAppearance()
    val values = (question.scaleLowerBound..question.scaleUpperBound).toList()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(45.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(appearance.ratingButtonColor)
                    .border(2.dp, appearance.borderColor, RoundedCornerShape(6.dp)),
        ) {
            values.forEachIndexed { index, value ->
                val isSelected = selectedValue == value
                val targetBg =
                    if (isSelected) appearance.ratingButtonActiveColor else Color.Transparent
                val animatedBg by animateColorAsState(
                    targetValue = targetBg,
                    label = "rating-segment-bg",
                )
                val textColor =
                    if (isSelected) {
                        appearance.ratingButtonActiveColor.contrastingTextColor()
                    } else {
                        appearance.textColor.copy(alpha = 0.5f)
                    }
                val drawBorderRight = index < values.lastIndex
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(animatedBg)
                            .clickable {
                                onSelect(if (isSelected) null else value)
                            }
                            .drawBehind {
                                if (drawBorderRight) {
                                    val strokeWidthPx = 1.dp.toPx()
                                    drawLine(
                                        color = appearance.borderColor,
                                        start = Offset(size.width - strokeWidthPx / 2f, 0f),
                                        end = Offset(size.width - strokeWidthPx / 2f, size.height),
                                        strokeWidth = strokeWidthPx,
                                    )
                                }
                            },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = value.toString(),
                        color = textColor,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        val lower = question.lowerBoundLabel
        val upper = question.upperBoundLabel
        if (lower.isNotBlank() || upper.isNotBlank()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
            ) {
                Text(
                    text = lower,
                    color = appearance.descriptionTextColor,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = upper,
                    color = appearance.descriptionTextColor,
                )
            }
        }
    }
}

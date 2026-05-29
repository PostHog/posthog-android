package com.posthog.android.surveys.compose.internal.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Shared list-of-choices renderer for [SingleChoice] and [MultipleChoice].
 *
 * Visual port of the iOS `MultipleChoiceOptions` view: each option is a
 * rounded bordered button that turns bold + checkmark-decorated when
 * selected. When [hasOpenChoice] is true the last option is treated as an
 * "other"-style free-text input that becomes editable while selected.
 */
@Composable
internal fun ChoiceOptions(
    options: List<String>,
    hasOpenChoice: Boolean,
    allowsMultipleSelection: Boolean,
    selectedOptions: Set<String>,
    onSelectedOptionsChange: (Set<String>) -> Unit,
    openChoiceInput: String,
    onOpenChoiceInputChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { index, option ->
            val isOpenChoice = hasOpenChoice && index == options.lastIndex
            val isSelected = option in selectedOptions
            ChoiceOption(
                option = option,
                isSelected = isSelected,
                isOpenChoice = isOpenChoice,
                openChoiceInput = openChoiceInput,
                onOpenChoiceInputChange = onOpenChoiceInputChange,
                onClick = {
                    val newSelected =
                        when {
                            isSelected -> selectedOptions - option
                            allowsMultipleSelection -> selectedOptions + option
                            else -> setOf(option)
                        }
                    onSelectedOptionsChange(newSelected)
                },
            )
        }
    }
}

@Composable
private fun ChoiceOption(
    option: String,
    isSelected: Boolean,
    isOpenChoice: Boolean,
    openChoiceInput: String,
    onOpenChoiceInputChange: (String) -> Unit,
    onClick: () -> Unit,
) {
    // iOS uses literal black / black.opacity(0.5) for the option borders + text — not
    // appearance-driven. Mirror that exactly so themed surveys look identical across
    // platforms.
    val color = if (isSelected) Color.Black else Color.Black.copy(alpha = 0.5f)
    val fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(4.dp))
                .border(1.dp, color, RoundedCornerShape(4.dp))
                .clickable(onClick = onClick)
                .padding(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isOpenChoice) "$option:" else option,
                    color = color,
                    fontWeight = fontWeight,
                )
                if (isOpenChoice && isSelected) {
                    BasicTextField(
                        value = openChoiceInput,
                        onValueChange = onOpenChoiceInputChange,
                        textStyle = TextStyle(color = Color.Black),
                        cursorBrush = SolidColor(Color.Black),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(24.dp),
                    )
                }
            }
            if (isSelected) {
                // iOS draws a custom check path (`CheckIcon` in `Resources.swift`).
                // Material's Icons.Filled.Check is visually equivalent and avoids
                // bundling another vector path purely for the check shape.
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(width = 16.dp, height = 12.dp),
                )
            }
        }
    }
}

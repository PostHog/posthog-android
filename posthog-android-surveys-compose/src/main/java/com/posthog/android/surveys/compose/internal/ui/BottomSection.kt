package com.posthog.android.surveys.compose.internal.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.posthog.android.surveys.compose.internal.theme.localAppearance

@Composable
internal fun BottomSection(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val appearance = localAppearance()
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = appearance.submitButtonColor,
                contentColor = appearance.submitButtonTextColor,
            ),
    ) {
        Text(text = label)
    }
}

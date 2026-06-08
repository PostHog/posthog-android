package com.posthog.android.surveys.compose.internal.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val appearance = localAppearance()
    Button(
        onClick = onClick,
        enabled = enabled,
        // Rounded rect rather than the Material 3 default capsule, to stay consistent with the
        // survey's input / rating / choice corners.
        shape = RoundedCornerShape(8.dp),
        modifier =
            modifier
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

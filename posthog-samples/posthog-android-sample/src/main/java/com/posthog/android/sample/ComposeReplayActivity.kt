package com.posthog.android.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.posthog.android.sample.ui.theme.postHogAndroidSampleTheme

/**
 * A fully Compose-rooted screen that recomposes on every frame via an infinite
 * animation. This is the shape that made session replay discard every screenshot:
 * the legacy redraw classifier cannot prove a Compose recomposition is animation-only.
 */
class ComposeReplayActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // "pixelOnly" redraws every frame without moving any mask geometry, which is
        // the shape the verified capture path is supposed to keep.
        val pixelOnly = intent?.getBooleanExtra("pixelOnly", false) == true
        setContent {
            postHogAndroidSampleTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    if (pixelOnly) PixelOnlyScreen() else AlwaysRecomposingScreen()
                }
            }
        }
    }
}

@Composable
private fun AlwaysRecomposingScreen() {
    val transition = rememberInfiniteTransition(label = "replay")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 1200, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "progress",
    )

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Compose replay probe", style = MaterialTheme.typography.headlineSmall)
        Text("This screen recomposes every frame.")
        // Reading `progress` here is what forces the per-frame recomposition.
        Text("progress = ${"%.3f".format(progress)}")
        Box(
            modifier =
                Modifier
                    .fillMaxWidth(progress.coerceAtLeast(0.05f))
                    .height(48.dp)
                    .background(Color(0xFF1D4AFF)),
        )
        Text("If replay works, this screen is not blank in the recording.")
    }
}

/** Redraws every frame, but every element keeps a fixed position and size. */
@Composable
private fun PixelOnlyScreen() {
    val transition = rememberInfiniteTransition(label = "pixelOnly")
    val hue by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 1200, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "hue",
    )

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Pixel-only Compose probe", style = MaterialTheme.typography.headlineSmall)
        Text("Colour changes every frame; nothing moves.")
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(Color(red = hue, green = 0.2f, blue = 1f - hue)),
        )
        Text("The verified path should keep these frames.")
    }
}

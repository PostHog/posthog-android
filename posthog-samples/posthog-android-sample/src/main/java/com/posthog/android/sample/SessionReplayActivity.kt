@file:Suppress("DEPRECATION")

package com.posthog.android.sample

import android.app.ProgressDialog
import android.content.Context
import android.os.Bundle
import android.widget.ProgressBar
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.posthog.PostHog
import com.posthog.android.sample.ui.theme.postHogAndroidSampleTheme

class SessionReplayActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            postHogAndroidSampleTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    SessionReplayScreen()
                }
            }
        }
    }
}

private var loaderDialog: ProgressDialog? = null

fun Context.showProgress() {
    loaderDialog?.dismiss()
    loaderDialog =
        ProgressDialog(this).apply {
            setMessage("Loading…")
            isIndeterminate = false
            setCancelable(false)
            show()
        }
}

@Suppress("ktlint:standard:function-naming")
@Composable
fun SessionReplayScreen() {
    val context = LocalContext.current
    var refreshKey by remember { mutableStateOf(0) }
    var showSpinner by remember { mutableStateOf(false) }

    val statusText =
        remember(refreshKey) {
            if (PostHog.isSessionReplayActive()) "🟢 Recording" else "🔴 Not Recording"
        }
    val sessionId =
        remember(refreshKey) {
            PostHog.getSessionId() ?: "N/A"
        }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Session Recording",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Status
        Text(text = statusText, style = MaterialTheme.typography.titleLarge)
        Text(
            text = "SID: $sessionId",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // Loader repro: separate ProgressDialog window
        Button(
            onClick = { context.showProgress() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF6C00)),
        ) {
            Text("Show ProgressDialog Loader")
        }

        // Loader repro: indeterminate spinner inside the tracked window
        Button(
            onClick = { showSpinner = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF6C00)),
        ) {
            Text("Show In-Window Spinner")
        }

        if (showSpinner) {
            AndroidView(
                factory = { ctx -> ProgressBar(ctx).apply { isIndeterminate = true } },
                modifier =
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .height(64.dp),
            )
        }

        Button(
            onClick = {
                loaderDialog?.dismiss()
                loaderDialog = null
                showSpinner = false
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF455A64)),
        ) {
            Text("Dismiss Loader")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // Manual Controls
        Text(
            text = "Manual Controls",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )

        Button(
            onClick = {
                PostHog.stopSessionReplay()
                refreshKey++
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
        ) {
            Text("Stop")
        }

        Button(
            onClick = {
                PostHog.startSessionReplay()
                refreshKey++
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Resume")
        }

        Button(
            onClick = {
                PostHog.startSessionReplay(resumeCurrent = false)
                refreshKey++
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Start New Session")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // Event Triggers
        Text(
            text = "Event Triggers",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )

        Button(
            onClick = {
                PostHog.stopSessionReplay()
                PostHog.startSessionReplay()
                refreshKey++
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7B1FA2)),
        ) {
            Text("Restart & Rotate Session Id")
        }

        Button(
            onClick = {
                PostHog.capture("start_replay_trigger_1")
                refreshKey++
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0)),
        ) {
            Text("Capture 'start_replay_trigger_1'")
        }

        Button(
            onClick = {
                PostHog.capture("start_replay_trigger_2")
                refreshKey++
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0)),
        ) {
            Text("Capture 'start_replay_trigger_2'")
        }
    }
}

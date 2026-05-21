package com.posthog.android.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.posthog.PostHog
import com.posthog.android.sample.ui.theme.postHogAndroidSampleTheme

class LogsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            postHogAndroidSampleTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        logsDemo()
                    }
                }
            }
        }
    }
}

/**
 * Dogfood section for the logs API. Tap each row to capture a log; the
 * "Last action" line shows what fired.
 */
@Composable
fun logsDemo(modifier: Modifier = Modifier) {
    var lastAction by remember { mutableStateOf("") }
    val attrs = mapOf("source" to "android-sample")
    val items =
        listOf(
            "trace" to { PostHog.logger.trace("LogsActivity trace tap", attrs) },
            "debug" to { PostHog.logger.debug("LogsActivity debug tap", attrs) },
            "info" to { PostHog.logger.info("LogsActivity info tap", attrs) },
            "warn" to { PostHog.logger.warn("LogsActivity warn tap", attrs) },
            "error" to { PostHog.logger.error("LogsActivity error tap", attrs) },
            "fatal" to { PostHog.logger.fatal("LogsActivity fatal tap", attrs) },
        )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
        Text("Logs (tap to capture):")
        items.forEach { (label, onTap) ->
            Text(
                text = "  • $label",
                modifier =
                    Modifier.clickable {
                        onTap()
                        lastAction = "Sent $label"
                    },
            )
        }
        Text(
            text = "  • flush all queues",
            modifier =
                Modifier.clickable {
                    PostHog.flush()
                    lastAction = "flush() called"
                },
        )
        if (lastAction.isNotEmpty()) {
            Text("Last action: $lastAction")
        }
    }
}

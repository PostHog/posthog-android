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

/**
 * Dogfood screen for exception steps. Record a few breadcrumb-style steps, then
 * capture an exception (manual or fatal) and confirm the buffered steps show up
 * under `$exception_steps` on the `$exception` event in PostHog.
 *
 * Fatal capture requires `errorTrackingConfig.autoCapture = true` (set in [MyApp])
 * and exception autocapture enabled for the project in PostHog.
 */
class ErrorTrackingActivity : ComponentActivity() {
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
                        exceptionStepsDemo()
                    }
                }
            }
        }
    }
}

@Composable
fun exceptionStepsDemo(modifier: Modifier = Modifier) {
    var stepsRecorded by remember { mutableStateOf(0) }
    var lastAction by remember { mutableStateOf("") }

    // Each tap records one breadcrumb step. Properties ride along under their own keys.
    val steps =
        remember {
            listOf<Pair<String, () -> Unit>>(
                "app opened" to { PostHog.addExceptionStep("App opened") },
                "tapped Checkout {screen: cart}" to {
                    PostHog.addExceptionStep("User tapped Checkout", mapOf("screen" to "cart"))
                },
                "added item {sku, qty}" to {
                    PostHog.addExceptionStep("Added item to cart", mapOf("sku" to "ABC-123", "qty" to 2))
                },
                "order request {status: 200}" to {
                    PostHog.addExceptionStep("POST /orders", mapOf("status" to 200, "durationMs" to 142))
                },
            )
        }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
        Text("Exception steps")
        Text("Steps recorded this session: $stepsRecorded")

        Text("Record steps (tap to add):")
        steps.forEach { (label, onTap) ->
            actionRow(label) {
                onTap()
                stepsRecorded += 1
                lastAction = "Recorded: $label"
            }
        }

        Text("Capture:")
        actionRow("Capture manual exception (attaches steps)") {
            PostHog.captureException(
                MyCustomException("Manual exception from sample"),
                mapOf("source" to "ErrorTrackingActivity"),
            )
            PostHog.flush()
            lastAction = "captureException() + flush()"
        }
        actionRow("Crash app (fatal — attaches steps)") {
            // Uncaught on the main thread: the SDK's UncaughtExceptionHandler captures
            // the $exception with the buffered steps; delivery typically completes on the
            // next launch via the persisted event queue.
            throw MyCustomException("Fatal crash from sample")
        }
        actionRow("flush all queues") {
            PostHog.flush()
            lastAction = "flush() called"
        }

        if (lastAction.isNotEmpty()) {
            Text("Last action: $lastAction")
        }
    }
}

@Composable
private fun actionRow(
    label: String,
    onClick: () -> Unit,
) {
    Text(text = "  • $label", modifier = Modifier.clickable(onClick = onClick))
}

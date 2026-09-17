package com.posthog.android.internal

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.posthog.android.PostHogAutocaptureModifier.postHogAutocaptureNoCapture
import android.widget.Button as NativeButton

/** Static, non-sensitive identifiers for interaction testing, with replay independently configurable. */
internal class InteractionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { InteractionProbeContent() }
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
internal fun InteractionProbeContent() {
    var responses by remember { mutableIntStateOf(0) }
    Column(Modifier.padding(24.dp)) {
        Text("Interaction capture probe")
        Text("Compose responses: $responses")
        Button(onClick = { responses++ }, modifier = Modifier.testTag("compose_responsive")) { Text("Compose responsive") }
        Button(onClick = {}, modifier = Modifier.testTag("compose_noop")) { Text("Compose no-op") }
        Column(Modifier.postHogAutocaptureNoCapture()) {
            Button(onClick = { responses++ }, modifier = Modifier.testTag("compose_ignored")) { Text("Compose ignored") }
        }
        AndroidView(factory = { context ->
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val status = TextView(context).apply { text = "View responses: 0" }
                var count = 0

                fun addButton(
                    resourceId: Int,
                    label: String,
                    onClick: () -> Unit,
                ) {
                    addView(
                        NativeButton(context).apply {
                            id = resourceId
                            text = label
                            setOnClickListener { onClick() }
                        },
                    )
                }
                addView(status)
                addButton(android.R.id.button1, "View responsive") { status.text = "View responses: ${++count}" }
                addButton(android.R.id.button2, "View no-op") { }
                addView(
                    LinearLayout(context).apply {
                        setTag(com.posthog.android.R.id.posthog_autocapture_no_capture, true)
                        addView(
                            NativeButton(context).apply {
                                id = android.R.id.button3
                                text = "View ignored"
                                setOnClickListener { status.text = "View responses: ${++count}" }
                            },
                        )
                    },
                )
            }
        })
    }
}

package com.posthog.android.sample

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.posthog.PostHog
import com.posthog.PostHogOkHttpInterceptor
import okhttp3.OkHttpClient
import okhttp3.internal.closeQuietly

class NormalActivity : ComponentActivity() {
    private val client =
        OkHttpClient.Builder()
            .addInterceptor(PostHogOkHttpInterceptor(captureNetworkTelemetry = true))
            .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.normal_activity)

        findViewById<Button>(R.id.sessionReplayButton).setOnClickListener {
            startActivity(Intent(this, SessionReplayActivity::class.java))
        }

        findViewById<Button>(R.id.logsButton).setOnClickListener {
            startActivity(Intent(this, LogsActivity::class.java))
        }

        findViewById<Button>(R.id.triggerSurveyButton).setOnClickListener {
            // Fires the event a Popover survey can be targeted on (event = "show_survey_trigger").
            PostHog.capture("show_survey_trigger")
            Toast.makeText(this, "Captured show_survey_trigger", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.openComposeHostButton).setOnClickListener {
            // Compose-hosted screen with one survey-trigger button per question type.
            startActivity(Intent(this, SurveysComposeActivity::class.java))
        }

        findViewById<Button>(R.id.button).setOnClickListener {
            // Makes a network request (captured by PostHogOkHttpInterceptor) when the
            // "enable_network_request" feature flag is on.
            val isNetworkRequestEnabled = PostHog.isFeatureEnabled("enable_network_request", false)
            if (isNetworkRequestEnabled) {
                Thread {
                    try {
                        client.newCall(
                            okhttp3.Request.Builder()
                                .url("https://google.com")
                                .build(),
                        ).execute().closeQuietly()
                        runOnUiThread {
                            Toast.makeText(this, "Network request successful!", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            Toast.makeText(this, "Network request failed: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            } else {
                Toast.makeText(
                    this,
                    "Network requests are disabled by feature flag: `enable_network_request`",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
}

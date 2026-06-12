package com.posthog.android.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.posthog.PostHog
import com.posthog.android.sample.ui.theme.postHogAndroidSampleTheme

/**
 * Compose-hosted screen used to verify the survey bottom sheet renders over a
 * Compose activity (the sibling [NormalActivity] covers the XML / View host).
 *
 * Each button captures a distinct event so a matching survey can be targeted on
 * it in PostHog — one per supported question type.
 */
class SurveysComposeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            postHogAndroidSampleTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    SurveyTriggers()
                }
            }
        }
    }
}

/**
 * The event name each button fires, paired with a human label. Target a survey
 * on the event name to test that question type from a Compose host.
 */
private val surveyTriggers =
    listOf(
        "Open text" to "show_open_text_survey",
        "Link" to "show_link_survey",
        "Number rating" to "show_number_rating_survey",
        "Emoji rating" to "show_emoji_rating_survey",
        "Thumbs up/down" to "show_thumbs_survey",
        "Single choice" to "show_single_choice_survey",
        "Multiple choice" to "show_multiple_choice_survey",
    )

@Composable
private fun SurveyTriggers(modifier: Modifier = Modifier) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Trigger a survey by type",
            style = MaterialTheme.typography.titleMedium,
        )
        surveyTriggers.forEach { (label, event) ->
            Button(
                onClick = { PostHog.capture(event) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(label)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SurveyTriggersPreview() {
    postHogAndroidSampleTheme {
        SurveyTriggers()
    }
}

package com.posthog.android.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import com.posthog.android.sample.ui.theme.PostHogAndroidSampleTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            PostHogAndroidSampleTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Greeting("Android")
                }
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    ClickableText(
        text = AnnotatedString("Hello $name!"),
        modifier = modifier,
        onClick = {
//            PostHog.optOut()
//            PostHog.optIn()
//            PostHog.identify("my_distinct_id", properties = mapOf("my_property" to 1), userProperties = mapOf("name" to "hello"))
//            PostHog.register("test", mapOf("one" to "two"))
//            PostHog.capture("testEvent", properties = mapOf("testProperty" to "testValue"))
//            PostHog.reloadFeatureFlagsRequest()
//            PostHog.isFeatureEnabled("sessionRecording")
//            val props = mutableMapOf<String, Any>()
//            props["test_key"] = "test_value"
//            PostHog.group("theType", "theKey", groupProperties = props)
//            PostHog.flush()
//            PostHog.reset()
        },
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    PostHogAndroidSampleTheme {
        Greeting("Android")
    }
}

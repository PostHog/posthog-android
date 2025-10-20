package com.posthog.android.sample

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

//        val webview = findViewById<WebView>(R.id.webview)
//        webview.loadUrl("https://www.google.com")

        val button = findViewById<Button>(R.id.button)
//        val editText = findViewById<EditText>(R.id.editText)
//        val imvAndroid = findViewById<ImageView>(R.id.imvAndroid)
        button.setOnClickListener {
//            Log.e("MyApp", "Clicked on button ${button.text}")
//            button.text = "Test: ${(0..10).random()}"
//            if (imvAndroid.visibility == View.VISIBLE) {
//                imvAndroid.visibility = View.GONE
//            } else {
//                imvAndroid.visibility = View.VISIBLE
//            }
//            startActivity(Intent(this, NothingActivity::class.java))
//            finish()
            // Check if the "enable_network_request" feature flag is enabled

//            var str: String? = null
//            if (str!!.startsWith("")) {
//                str = "123"
//                Toast.makeText(this, str, Toast.LENGTH_SHORT).show()
//            }
            val doSomething = DoSomething()
            doSomething.doSomethingNow()

            val isNetworkRequestEnabled = PostHog.isFeatureEnabled("enable_network_request", false)

            if (isNetworkRequestEnabled) {
                // Make the network request
                Thread {
                    try {
                        client.newCall(
                            okhttp3.Request.Builder()
                                .url("https://google.com")
                                .build(),
                        ).execute().closeQuietly()

                        // Show success message on the main thread
                        runOnUiThread {
                            Toast.makeText(this, "Network request successful!", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        // Show error message on the main thread
                        runOnUiThread {
                            Toast.makeText(this, "Network request failed: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            } else {
                // Show message that feature is disabled
                Toast.makeText(this, "Network requests are disabled by feature flag: `enable_network_request`", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

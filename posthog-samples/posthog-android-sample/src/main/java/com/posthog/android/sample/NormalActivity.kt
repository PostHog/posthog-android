package com.posthog.android.sample

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.posthog.PostHog
import com.posthog.PostHogOkHttpInterceptor
import okhttp3.OkHttpClient
import okhttp3.internal.closeQuietly

class NormalActivity : ComponentActivity() {
    private val client =
        OkHttpClient.Builder()
            .addInterceptor(PostHogOkHttpInterceptor(captureNetworkTelemetry = true))
            .build()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.d(TAG, "FCM POST_NOTIFICATIONS permission granted")
        } else {
            Log.w(TAG, "FCM POST_NOTIFICATIONS permission denied - notifications will not be displayed")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.normal_activity)

        // Request notification permission for Android 13+ (API 33+)
        requestNotificationPermission()

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

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    Log.d(TAG, "FCM POST_NOTIFICATIONS permission already granted")
                }
                else -> {
                    Log.d(TAG, "FCM Requesting POST_NOTIFICATIONS permission")
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            Log.d(TAG, "FCM POST_NOTIFICATIONS permission not required (Android < 13)")
        }
    }

    companion object {
        private const val TAG = "NormalActivity"
    }
}

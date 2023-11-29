package com.posthog.android.sample

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import androidx.activity.ComponentActivity
import com.posthog.PostHogOkHttpInterceptor
import okhttp3.OkHttpClient

class NormalActivity : ComponentActivity() {

    private val client = OkHttpClient.Builder()
        .addInterceptor(PostHogOkHttpInterceptor())
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.normal_activity)

        val button = findViewById<Button>(R.id.button)
        val imvAndroid = findViewById<ImageView>(R.id.imvAndroid)
        button.setOnClickListener {
            Log.e("MyApp", "Clicked on button ${button.text}")
            button.text = "Test: ${(0..10).random()}"
            if (imvAndroid.visibility == View.VISIBLE) {
                imvAndroid.visibility = View.GONE
            } else {
                imvAndroid.visibility = View.VISIBLE
            }
//            startActivity(Intent(this, NothingActivity::class.java))
//            finish()
            Thread {
                client.newCall(
                    okhttp3.Request.Builder()
                        .url("https://google.com")
                        .build(),
                ).execute()
            }.start()
        }
    }
}

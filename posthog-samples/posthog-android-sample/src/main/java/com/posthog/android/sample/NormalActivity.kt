package com.posthog.android.sample

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import androidx.activity.ComponentActivity
// import android.widget.Toast

class NormalActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.normal_activity)

        val button = findViewById<Button>(R.id.button)
        val imvAndroid = findViewById<ImageView>(R.id.imvAndroid)
        button.setOnClickListener {
            // do nothing
//            Toast.makeText(this, "Hi!", Toast.LENGTH_SHORT).show()
            button.text = "Test: ${(0..10).random()}"
            if (imvAndroid.visibility == View.VISIBLE) {
                imvAndroid.visibility = View.GONE
            } else {
                imvAndroid.visibility = View.VISIBLE
            }
        }
    }
}

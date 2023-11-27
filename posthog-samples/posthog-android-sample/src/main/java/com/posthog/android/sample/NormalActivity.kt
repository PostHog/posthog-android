package com.posthog.android.sample

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.activity.ComponentActivity

class NormalActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.normal_activity)

        val button = findViewById<Button>(R.id.button)
//        val imvAndroid = findViewById<ImageView>(R.id.imvAndroid)
        button.setOnClickListener {
            // do nothing
//            Toast.makeText(this, "Hi!", Toast.LENGTH_SHORT).show()
//            button.text = "Test: ${(0..10).random()}"
//            if (imvAndroid.visibility == View.VISIBLE) {
//                imvAndroid.visibility = View.GONE
//            } else {
//                imvAndroid.visibility = View.VISIBLE
//            }
            startActivity(Intent(this, NothingActivity::class.java))
        }
    }
}

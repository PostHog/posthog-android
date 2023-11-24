package com.posthog.android.sample

import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.ComponentActivity

class NormalActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.normal_activity)

        val button = findViewById<Button>(R.id.button)
        button.setOnClickListener {
            // do nothing
            Toast.makeText(this, "Hi!", Toast.LENGTH_SHORT).show()
            button.text = "test 2"
        }
    }
}

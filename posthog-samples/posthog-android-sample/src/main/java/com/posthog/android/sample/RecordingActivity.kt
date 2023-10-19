package com.posthog.android.sample

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.hbisoft.hbrecorder.HBRecorder
import com.hbisoft.hbrecorder.HBRecorderListener
import com.posthog.PostHog
import com.posthog.PostHogAttachment
import java.io.File
import java.nio.file.Files

class RecordingActivity : ComponentActivity(), HBRecorderListener {
    private val SCREEN_RECORD_REQUEST_CODE = 777
    private val PERMISSION_REQ_ID_RECORD_AUDIO = 22
    private val PERMISSION_REQ_POST_NOTIFICATIONS = 33
    private val PERMISSION_REQ_ID_WRITE_EXTERNAL_STORAGE = PERMISSION_REQ_ID_RECORD_AUDIO + 1
    private var hasPermissions = false

    lateinit var hbRecorder: HBRecorder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_recording)

        // Init HBRecorder
        hbRecorder = HBRecorder(this, this)

        findViewById<Button>(R.id.start_recording_button).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (checkSelfPermission(
                        Manifest.permission.POST_NOTIFICATIONS,
                        PERMISSION_REQ_POST_NOTIFICATIONS,
                    ) && checkSelfPermission(
                        Manifest.permission.RECORD_AUDIO,
                        PERMISSION_REQ_ID_RECORD_AUDIO,
                    )
                ) {
                    hasPermissions = true
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (checkSelfPermission(
                        Manifest.permission.RECORD_AUDIO,
                        PERMISSION_REQ_ID_RECORD_AUDIO,
                    )
                ) {
                    hasPermissions = true
                }
            } else {
                if (checkSelfPermission(
                        Manifest.permission.RECORD_AUDIO,
                        PERMISSION_REQ_ID_RECORD_AUDIO,
                    ) && checkSelfPermission(
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        PERMISSION_REQ_ID_WRITE_EXTERNAL_STORAGE,
                    )
                ) {
                    hasPermissions = true
                }
            }
            if (hasPermissions) {
                // check if recording is in progress
                // and stop it if it is
                if (hbRecorder.isBusyRecording) {
                    hbRecorder.stopScreenRecording()
                } else {
                    startRecordingScreen()
                }
            }
        }

        findViewById<Button>(R.id.stop_recording_button).setOnClickListener {
            hbRecorder.stopScreenRecording()
            hbRecorder.filePath?.let {
                println(it)
                val file = File(it)
                val mediaType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Files.probeContentType(file.toPath())
                } else {
                    "video/mp4"
                }
                if (file.exists()) {
                    PostHog.feedback(mapOf("name" to "Manoel"), attachment = PostHogAttachment(mediaType, file))
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String?>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSION_REQ_POST_NOTIFICATIONS -> if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                checkSelfPermission(
                    Manifest.permission.RECORD_AUDIO,
                    PERMISSION_REQ_ID_RECORD_AUDIO,
                )
            } else {
                hasPermissions = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    showLongToast("No permission for " + Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            PERMISSION_REQ_ID_RECORD_AUDIO -> if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                checkSelfPermission(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    PERMISSION_REQ_ID_WRITE_EXTERNAL_STORAGE,
                )
            } else {
                hasPermissions = false
                showLongToast("No permission for " + Manifest.permission.RECORD_AUDIO)
            }

            PERMISSION_REQ_ID_WRITE_EXTERNAL_STORAGE -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                hasPermissions = true
                startRecordingScreen()
            } else {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    hasPermissions = true
                    // Permissions was provided
                    // Start screen recording
                    startRecordingScreen()
                } else {
                    hasPermissions = false
                    showLongToast("No permission for " + Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }

            else -> {}
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == SCREEN_RECORD_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                // Set file path or Uri depending on SDK version
                setOutputPath()
                // Start screen recording
                hbRecorder.startScreenRecording(data, resultCode)
            }
        }
    }

    private fun setOutputPath() {
        createFolder()
        hbRecorder.setOutputPath(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                .toString() + "/HBRecorder",
        )
    }

    private fun createFolder() {
        val f1 = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "HBRecorder",
        )
        if (!f1.exists()) {
            if (f1.mkdirs()) {
                Log.i("Folder ", "created")
            }
        }
    }

    private fun checkSelfPermission(permission: String, requestCode: Int): Boolean {
        if (ContextCompat.checkSelfPermission(
                this,
                permission,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(permission), requestCode)
            return false
        }
        return true
    }

    private fun showLongToast(msg: String) {
        Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
    }

    private fun quickSettings() {
        hbRecorder.setAudioBitrate(128000)
        hbRecorder.setAudioSamplingRate(44100)
    }

    @Suppress("DEPRECATION")
    private fun startRecordingScreen() {
        quickSettings()
        val mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val permissionIntent = mediaProjectionManager.createScreenCaptureIntent()
        startActivityForResult(permissionIntent, SCREEN_RECORD_REQUEST_CODE)
    }

    override fun HBRecorderOnStart() {
    }

    override fun HBRecorderOnComplete() {
    }

    override fun HBRecorderOnError(errorCode: Int, reason: String?) {
        println(reason)
    }

    override fun HBRecorderOnPause() {
    }

    override fun HBRecorderOnResume() {
    }
}

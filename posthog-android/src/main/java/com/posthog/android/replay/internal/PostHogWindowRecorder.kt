package com.posthog.android.replay.internal

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.CanvasDelegate
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.FrameMetrics
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import java.util.LinkedList

// TODO: make Activity weakref
internal class PostHogWindowRecorder(private val activity: Activity) : Window.OnFrameMetricsAvailableListener {
    companion object {
        private const val MIN_TIME_BETWEEN_FRAMES_MS = 500
    }

    private val recorder = RRWebRecorder()
    private var canvasDelegate: CanvasDelegate? = null
    private var canvas: Canvas? = null
    private var lastCapturedAtMs: Long? = null

    override fun onFrameMetricsAvailable(
        window: Window?,
        frameMetrics: FrameMetrics?,
        dropCountSinceLastInvocation: Int,
    ) {
        val view = activity.window?.decorView
        view?.let {
            captureFrame(it)
        }
    }

    fun startRecording() {
        pauseRecording()

        activity.window?.addOnFrameMetricsAvailableListener(this, Handler(Looper.getMainLooper()))

        val defaultCallback = activity.window?.callback ?: PostHogEmptyCallback
        activity.window?.callback = object : PostHogWindowDelegate(defaultCallback) {
            override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
                event?.let {
                    val timestamp = System.currentTimeMillis()
                    recorder.onTouchEvent(timestamp, event)
                }
                return super.dispatchTouchEvent(event)
            }
        }
    }

    fun pauseRecording() {
        try {
            activity.window?.removeOnFrameMetricsAvailableListener(this)
        } catch (e: Throwable) {
            // ignore
        }

        if (activity.window?.callback is PostHogEmptyCallback) {
            activity.window.callback = null
        } else if (activity.window?.callback is PostHogWindowDelegate) {
            activity.window.callback = (activity.window.callback as PostHogWindowDelegate).callback
        }
    }

    fun stopRecording(): List<RREvent> {
        pauseRecording()
        return recorder.recording

// val json = """
// {
//  "properties": {
//    "$snapshot_bytes": 60,
//    "$snapshot_data": [
//      {
//        "type": 3,
//        "data": {
//          "source": 1
//        }
//      },
//      {
//        "type": 3,
//        "data": {
//          "source": 2
//        }
//      }
//    ],
//    "$session_id": "sessionId",
//    "$window_id": "windowId"
//  },
//  "timestamp": "2023-10-25T14:14:04.407Z",
//  "event": "$snapshot",
//  "distinct_id": "theID"
// }
// """.trimIndent()
    }

    @Suppress("DEPRECATION")
    private fun captureFrame(view: View) {
        if (view.width == 0 || view.height == 0 || view.visibility == View.GONE) {
            return
        }

        // cheap debounce for testing
        // TODO remove
        val now = SystemClock.uptimeMillis()
        val localLastCapturedAtMs = lastCapturedAtMs
        if (localLastCapturedAtMs != null && (now - localLastCapturedAtMs) < MIN_TIME_BETWEEN_FRAMES_MS) {
            return
        }
        lastCapturedAtMs = now

        var localCanvasDelegate = canvasDelegate
        if (localCanvasDelegate == null) {
            val displayMetrics = DisplayMetrics()
            activity.windowManager.defaultDisplay.getMetrics(displayMetrics)
            val bitmap = Bitmap.createBitmap(
                displayMetrics.widthPixels,
                displayMetrics.heightPixels,
                Bitmap.Config.ARGB_8888,
            )
            val localCanvas = Canvas(bitmap)
            canvas = localCanvas
            localCanvasDelegate = CanvasDelegate(
                recorder,
                localCanvas,
            )
        }
        canvasDelegate = localCanvasDelegate

        // reset the canvas first, as it will be re-used for clipping operations
        localCanvasDelegate.restoreToCount(1)
        recorder.beginFrame(System.currentTimeMillis(), view.width, view.height)

        val location = IntArray(2)
        val items = LinkedList<View?>()
        items.add(view)
        while (!items.isEmpty()) {
            val item = items.removeFirst()
            if (item != null && item.visibility == View.VISIBLE) {
                if (item.tag == "exclude") {
                    // skip excluded widgets
                } else if (item is ViewGroup && item.willNotDraw()) {
                    // skip layouts which don't draw anything
                } else {
                    item.getLocationOnScreen(location)
                    val x = location[0].toFloat() + item.translationX
                    val y = location[1].toFloat() + item.translationY

                    val saveCount = localCanvasDelegate.save()
                    recorder.translate(
                        x,
                        y,
                    )
                    ViewHelper.executeOnDraw(item, localCanvasDelegate)
                    localCanvasDelegate.restoreToCount(saveCount)
                }

                if (item is ViewGroup) {
                    val childCount = item.childCount
                    for (i in 0 until childCount) {
                        items.add(item.getChildAt(i))
                    }
                }
            }
        }
    }
}

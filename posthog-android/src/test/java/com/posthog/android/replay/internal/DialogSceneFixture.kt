package com.posthog.android.replay.internal

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.io.Closeable

internal class DialogSceneFixture : Closeable {
    private val controller = Robolectric.buildActivity(Activity::class.java).setup()
    val activity = controller.get()
    val content = FrameLayout(activity)
    val masked =
        TextView(activity).apply {
            text = "sensitive content"
            tag = "ph-no-capture"
        }
    val dialog = Dialog(activity)
    val root: View

    init {
        content.addView(masked)
        dialog.setContentView(content)
        dialog.window!!.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        shadowOf(Looper.getMainLooper()).idle()
        root = dialog.window!!.decorView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 600, 1000)
        layoutContent()
        for (view in listOf(activity.window.decorView, root)) {
            val info = View::class.java.getDeclaredField("mAttachInfo").apply { isAccessible = true }.get(view)
            info.javaClass.getDeclaredField("mWindowVisibility").apply { isAccessible = true }.setInt(info, View.VISIBLE)
        }
    }

    fun layoutContent() {
        content.layout(0, 0, 600, 1000)
        masked.layout(40, 50, 240, 100)
    }

    override fun close() {
        dialog.dismiss()
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }
}

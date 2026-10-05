package com.posthog.android.replay.internal

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.io.Closeable

@Suppress("DEPRECATION")
internal class MaterialDialogFixture : Closeable {
    val controller = Robolectric.buildActivity(Activity::class.java).setup()
    val activity = controller.get()
    val content = FrameLayout(activity)
    val masked =
        TextView(activity).apply {
            text = "sensitive content"
            tag = "ph-no-capture"
        }
    val dialog: BottomSheetDialog
    val root: View
    val sheet: FrameLayout

    init {
        activity.setTheme(com.google.android.material.R.style.Theme_MaterialComponents_Light_NoActionBar)
        content.addView(masked)
        dialog = BottomSheetDialog(activity)
        dialog.setContentView(content)
        dialog.window!!.navigationBarColor = Color.TRANSPARENT
        dialog.window!!.statusBarColor = Color.TRANSPARENT
        dialog.show()
        shadowOf(Looper.getMainLooper()).idle()
        root = dialog.window!!.decorView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 600, 1000)
        sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet)!!
        sheet.background = ColorDrawable(Color.WHITE)
        placeSheet()
        for (view in listOf(activity.window.decorView, root)) {
            val info = View::class.java.getDeclaredField("mAttachInfo").apply { isAccessible = true }.get(view)
            info.javaClass.getDeclaredField("mWindowVisibility").apply { isAccessible = true }.setInt(info, View.VISIBLE)
        }
    }

    fun placeSheet(
        left: Int = 50,
        top: Int = 600,
        width: Int = 500,
        height: Int = 300,
    ) {
        sheet.layout(left, top, left + width, top + height)
        content.layout(0, 0, width, height)
        masked.layout(40, 50, 240, 100)
        sheet.background?.setBounds(0, 0, width, height)
    }

    override fun close() {
        dialog.dismiss()
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }
}

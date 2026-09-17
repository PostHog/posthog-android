package com.posthog.android.internal

import android.content.Context
import android.content.res.Resources
import android.util.DisplayMetrics
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.android.R
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
internal class InteractionTapTrackerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val root = FrameLayout(context).apply { layout(0, 0, 400, 400) }
    private val button =
        Button(context).also {
            root.addView(it)
            it.layout(0, 0, 200, 200)
            it.isClickable = true
        }
    private val tracker = InteractionTapTracker()

    private fun touch(
        action: Int,
        time: Long = 10,
        x: Float = 20f,
    ): InteractionTarget? {
        val event = MotionEvent.obtain(0, time, action, x, 20f, 0)
        return try {
            tracker.onTouch(root, event)
        } finally {
            event.recycle()
        }
    }

    @Test
    fun `tap properties snapshot window local dp rather than screen pixels`() {
        val offsetRoot = mock<View>()
        val resources = mock<Resources>()
        val metrics = DisplayMetrics().apply { density = 2f }
        whenever(offsetRoot.resources).thenReturn(resources)
        whenever(resources.displayMetrics).thenReturn(metrics)
        doAnswer {
            val location = it.getArgument<IntArray>(0)
            location[0] = 100
            location[1] = 200
            null
        }.whenever(offsetRoot).getLocationOnScreen(any())
        doAnswer {
            val location = it.getArgument<IntArray>(0)
            location[0] = 10
            location[1] = 20
            null
        }.whenever(offsetRoot).getLocationInWindow(any())
        val event = MotionEvent.obtain(0, 10, MotionEvent.ACTION_UP, 180f, 300f, 0)
        try {
            val target = InteractionTarget(offsetRoot, elements = listOf(InteractionElement("button")))
            val properties = interactionPropertiesForTap(target, offsetRoot, event)
            assertEquals("touch", properties["\$event_type"])
            assertEquals(45f, properties["\$touch_x"])
            assertEquals(60f, properties["\$touch_y"])
            metrics.density = 3f
            assertEquals(45f, properties["\$touch_x"])
            assertEquals(180f, event.rawX)
            assertEquals(300f, event.rawY)
        } finally {
            event.recycle()
        }
    }

    @Test
    fun `single tap resolves before host mutation`() {
        assertNull(touch(MotionEvent.ACTION_DOWN, 0))
        assertNotNull(touch(MotionEvent.ACTION_UP))
        assertNull(touch(MotionEvent.ACTION_UP))
    }

    @Test
    fun `scroll and moving back cancel tap`() {
        touch(MotionEvent.ACTION_DOWN, 0)
        touch(MotionEvent.ACTION_MOVE, 5, 150f)
        assertNull(touch(MotionEvent.ACTION_UP))
    }

    @Test
    fun `long press cancel pointer changes and orphan up are ignored`() {
        assertNull(touch(MotionEvent.ACTION_UP))
        touch(MotionEvent.ACTION_DOWN, 0)
        assertNull(touch(MotionEvent.ACTION_UP, ViewConfiguration.getLongPressTimeout().toLong()))
        touch(MotionEvent.ACTION_DOWN, 0)
        touch(MotionEvent.ACTION_CANCEL)
        assertNull(touch(MotionEvent.ACTION_UP))
        touch(MotionEvent.ACTION_DOWN, 0)
        touch(MotionEvent.ACTION_POINTER_DOWN)
        assertNull(touch(MotionEvent.ACTION_UP))
    }

    @Test
    fun `exclusion and target changes during tap are re-evaluated`() {
        touch(MotionEvent.ACTION_DOWN, 0)
        button.setTag(R.id.posthog_autocapture_no_capture, true)
        assertNull(touch(MotionEvent.ACTION_UP))
        button.setTag(R.id.posthog_autocapture_no_capture, false)
        touch(MotionEvent.ACTION_DOWN, 0)
        root.removeView(button)
        val replacement =
            Button(context).also {
                root.addView(it)
                it.layout(0, 0, 200, 200)
                it.isClickable = true
            }
        assertNull(touch(MotionEvent.ACTION_UP))
        touch(MotionEvent.ACTION_DOWN, 0)
        replacement.isEnabled = false
        assertNull(touch(MotionEvent.ACTION_UP))
    }

    @Test
    fun `batched movement and multi touch invalidate the gesture`() {
        touch(MotionEvent.ACTION_DOWN, 0)
        val event = MotionEvent.obtain(0, 5, MotionEvent.ACTION_MOVE, 150f, 20f, 0)
        event.addBatch(10, 20f, 20f, 1f, 1f, 0)
        try {
            tracker.onTouch(root, event)
        } finally {
            event.recycle()
        }
        assertNull(touch(MotionEvent.ACTION_UP, 20))
        touch(MotionEvent.ACTION_DOWN, 0)
        val pointers = Array(2) { MotionEvent.PointerProperties().apply { id = it } }
        val coords =
            Array(2) {
                MotionEvent.PointerCoords().apply {
                    x = 20f
                    y = 20f
                }
            }
        val multi = MotionEvent.obtain(0, 5, MotionEvent.ACTION_MOVE, 2, pointers, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        try {
            tracker.onTouch(root, multi)
        } finally {
            multi.recycle()
        }
        assertNull(touch(MotionEvent.ACTION_UP))
    }
}

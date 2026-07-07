package com.posthog.android.replay.internal

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
internal class AnimationDetectionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun <T : View> T.laidOut(
        w: Int = 100,
        h: Int = 100,
    ): T = apply { layout(0, 0, w, h) }

    @Test
    fun `detects an indeterminate progress bar (spinner)`() {
        val bar = ProgressBar(context).apply { isIndeterminate = true }.laidOut()

        assertNotNull(bar.findRunningIndeterminateProgressBar())
    }

    @Test
    fun `detects an indeterminate progress bar nested in a view group`() {
        val bar = ProgressBar(context).apply { isIndeterminate = true }
        val root =
            FrameLayout(context).apply {
                addView(TextView(context))
                addView(bar)
            }
        // Lay views out after assembly: addView clears any bounds set beforehand.
        root.layout(0, 0, 200, 200)
        bar.layout(0, 0, 100, 100)

        assertNotNull(root.findRunningIndeterminateProgressBar())
    }

    @Test
    fun `ignores a determinate progress bar`() {
        val bar =
            ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
                .apply { isIndeterminate = false }
                .laidOut()

        assertNull(bar.findRunningIndeterminateProgressBar())
    }

    @Test
    fun `ignores a tree without any progress bar`() {
        val root =
            FrameLayout(context).apply {
                addView(TextView(context).laidOut())
            }.laidOut(200, 200)

        assertNull(root.findRunningIndeterminateProgressBar())
    }

    @Test
    fun `ignores an indeterminate progress bar that is not visible`() {
        val bar =
            ProgressBar(context)
                .apply {
                    isIndeterminate = true
                    visibility = View.GONE
                }.laidOut()

        assertNull(bar.findRunningIndeterminateProgressBar())
    }

    @Test
    fun `ignores an indeterminate progress bar that has not been laid out`() {
        val bar = ProgressBar(context).apply { isIndeterminate = true }

        assertNull(bar.findRunningIndeterminateProgressBar())
    }

    @Test
    fun `ignores a visible spinner under a gone ancestor`() {
        val bar = ProgressBar(context).apply { isIndeterminate = true }
        val root =
            FrameLayout(context).apply {
                addView(bar)
                visibility = View.GONE
            }
        root.layout(0, 0, 200, 200)
        bar.layout(0, 0, 100, 100)

        assertNull(root.findRunningIndeterminateProgressBar())
    }

    @Test
    fun `ignores a fully transparent spinner`() {
        val bar =
            ProgressBar(context)
                .apply {
                    isIndeterminate = true
                    alpha = 0f
                }.laidOut()

        assertNull(bar.findRunningIndeterminateProgressBar())
    }
}

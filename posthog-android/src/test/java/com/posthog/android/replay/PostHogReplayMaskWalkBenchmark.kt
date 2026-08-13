package com.posthog.android.replay

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.android.API_KEY
import com.posthog.android.PostHogAndroidConfig
import com.posthog.android.internal.MainHandler
import com.posthog.android.replay.internal.WindowDrawState
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Manual benchmark for the session replay screenshot mask-walk hot paths. Not a CI test:
 * gated behind POSTHOG_BENCHMARK=1 so it never runs (or flakes) in a normal build.
 *
 *   POSTHOG_BENCHMARK=1 ./gradlew :posthog-android:testReleaseUnitTest \
 *     --tests '*PostHogReplayMaskWalkBenchmark*' -i | grep BENCH
 *
 * Times are JVM/Robolectric, so absolute values differ from ART on a device; they are
 * meant for relative before/after comparison. Allocation bytes are exact per-thread
 * TLAB counts from the JVM and translate directly to ART GC pressure.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [26], qualifiers = "w1080dp-h1920dp-mdpi")
internal class PostHogReplayMaskWalkBenchmark {
    private lateinit var sut: PostHogReplayIntegration
    private lateinit var activity: Activity

    @BeforeTest
    fun setUp() {
        assumeTrue(System.getenv("POSTHOG_BENCHMARK") == "1")
        val config =
            PostHogAndroidConfig(API_KEY).apply {
                sessionReplayConfig.screenshot = true
            }
        sut = PostHogReplayIntegration(ApplicationProvider.getApplicationContext(), config, MainHandler())
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        makeWindowVisible(activity.window.decorView)
    }

    @Test
    fun `benchmark mask walk hot paths`() {
        benchmarkTree("small-60-views", sections = 2, rowsPerSection = 2, leavesPerRow = 12)
        benchmarkTree("large-550-views", sections = 8, rowsPerSection = 5, leavesPerRow = 12)
    }

    private fun benchmarkTree(
        label: String,
        sections: Int,
        rowsPerSection: Int,
        leavesPerRow: Int,
    ) {
        val root = buildTree(activity, sections, rowsPerSection, leavesPerRow)
        activity.setContentView(root)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        // The content frame is slightly shorter than the display (status bar), so lay the
        // tree out to the frame's real bounds or clipping hides part of it from the walk.
        val contentFrame = root.parent as View
        layoutTree(root, 0, 0, contentFrame.width, contentFrame.height)

        // Every 4th leaf is a TextView with text, masked by the default maskAllTextInputs=true.
        val expectedRects = sections * rowsPerSection * leavesPerRow / 4
        val baseline = collectWalkRects(root)
        assertEquals(
            expectedRects,
            baseline.size,
            "benchmark tree is not visible to the walk; results would be meaningless",
        )

        // 1. Endpoint walk (the pre/post-copy walks, one of each per capture).
        measure("endpoint-walk[$label]", warmup = 200, iterations = 1000) {
            collectWalkRects(root)
        }

        // 2. Draw-time walk while a capture is in flight (per frame during capture windows).
        val drawState = WindowDrawState()
        val token = drawState.beginMaskCapture()
        drawState.setBaseline(token, baseline)
        measure("draw-walk-during-capture[$label]", warmup = 200, iterations = 1000) {
            sut.onDrawCallback(root, drawState)
        }

        // 3. Per-draw overhead with no capture in flight (every frame, 60-120x per second).
        val idleDrawState = WindowDrawState()
        measure("draw-idle[$label]", warmup = 100_000, iterations = 2_000_000) {
            sut.onDrawCallback(root, idleDrawState)
        }

        (root.parent as? ViewGroup)?.removeView(root)
    }

    private fun collectWalkRects(root: View): List<android.graphics.Rect> {
        val walk = PostHogReplayIntegration.MaskWalk()
        with(sut) { findMaskableWidgets(root, walk) }
        return walk.rects
    }

    private fun measure(
        name: String,
        warmup: Int,
        iterations: Int,
        op: () -> Unit,
    ) {
        repeat(warmup) { op() }

        val allocatedBefore = threadAllocatedBytes()
        val batches = 5
        val batchNs = LongArray(batches)
        for (b in 0 until batches) {
            val start = System.nanoTime()
            repeat(iterations / batches) { op() }
            batchNs[b] = (System.nanoTime() - start) / (iterations / batches)
        }
        val bytesPerOp = (threadAllocatedBytes() - allocatedBefore) / iterations
        batchNs.sort()

        println("BENCH $name ns/op=${batchNs[batches / 2]} bytes/op=$bytesPerOp")
    }

    // java.lang.management is not on the Android unit-test compile classpath, but tests
    // execute on the host JVM where it exists; reach it via reflection.
    @Suppress("DEPRECATION")
    private fun threadAllocatedBytes(): Long {
        return try {
            val factory = Class.forName("java.lang.management.ManagementFactory")
            val bean = factory.getMethod("getThreadMXBean").invoke(null)
            val method =
                Class.forName("com.sun.management.ThreadMXBean")
                    .getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
            method.invoke(bean, Thread.currentThread().id) as Long
        } catch (e: Throwable) {
            0L
        }
    }

    // sections x (2 nested wrappers + rowsPerSection rows) x leavesPerRow leaves; every 4th
    // leaf a TextView with text so the walk produces mask rects, the rest plain Views.
    private fun buildTree(
        context: Context,
        sections: Int,
        rowsPerSection: Int,
        leavesPerRow: Int,
    ): ViewGroup {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        var leafIndex = 0
        repeat(sections) {
            val wrapper = FrameLayout(context)
            val inner = FrameLayout(context)
            val section = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            wrapper.addView(inner)
            inner.addView(section)
            root.addView(wrapper)
            repeat(rowsPerSection) {
                val row = LinearLayout(context)
                section.addView(row)
                repeat(leavesPerRow) {
                    val leaf =
                        if (leafIndex % 4 == 3) {
                            TextView(context).apply { text = "Item $leafIndex" }
                        } else {
                            View(context)
                        }
                    row.addView(leaf)
                    leafIndex++
                }
            }
        }
        return root
    }

    private fun layoutTree(
        view: View,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) {
        view.layout(left, top, right, bottom)
        if (view is ViewGroup && view.childCount > 0) {
            val childHeight = maxOf(1, (bottom - top) / view.childCount)
            for (i in 0 until view.childCount) {
                layoutTree(view.getChildAt(i), 0, i * childHeight, right - left, (i + 1) * childHeight)
            }
        }
    }

    private fun makeWindowVisible(decorView: View) {
        val attachInfo =
            View::class.java.getDeclaredField("mAttachInfo")
                .apply { isAccessible = true }
                .get(decorView)
        attachInfo.javaClass.getDeclaredField("mWindowVisibility")
            .apply { isAccessible = true }
            .setInt(attachInfo, View.VISIBLE)
    }
}

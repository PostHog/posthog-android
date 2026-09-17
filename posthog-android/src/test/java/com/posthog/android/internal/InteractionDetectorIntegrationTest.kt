package com.posthog.android.internal

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager.LayoutParams.FLAG_SECURE
import android.widget.Button
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.PostHog
import com.posthog.PostHogEvent
import com.posthog.android.API_KEY
import com.posthog.android.PostHogAndroidConfig
import com.posthog.internal.PostHogMemoryPreferences
import curtains.Curtains
import curtains.DispatchState
import curtains.OnRootViewsChangedListener
import curtains.touchEventInterceptors
import org.junit.runner.RunWith
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.mock
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
internal class InteractionDetectorIntegrationTest {
    private val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
    private val activity = controller.get()
    private val button =
        Button(activity).apply {
            text = "Action"
            isClickable = true
        }
    private val roots = mutableListOf<OnRootViewsChangedListener>()
    private val events = mutableListOf<PostHogEvent>()
    private val curtains = mockStatic(Curtains::class.java)

    @Suppress("DEPRECATION")
    private val config =
        PostHogAndroidConfig(API_KEY).apply {
            captureRageClicks = true
            captureDeadClicks = true
            sessionReplay = false
            cachePreferences = PostHogMemoryPreferences()
            remoteConfig = false
            preloadFeatureFlags = false
            addBeforeSend { event ->
                if (event.event in listOf("\$rageclick", "\$dead_click", "\$autocapture")) events += event
                null
            }
        }
    private val sut = PostHogElementInteractionIntegration(config)
    private val client: PostHog

    init {
        activity.setContentView(button)
        val decor = activity.window.decorView
        curtains.`when`<List<View>> { Curtains.rootViews }.thenReturn(listOf(decor))
        curtains.`when`<MutableList<OnRootViewsChangedListener>> { Curtains.onRootViewsChangedListeners }.thenReturn(roots)
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, 400, 800)
        val info = ReflectionHelpers.getField<Any>(decor, "mAttachInfo")
        ReflectionHelpers.setField(info, "mWindowVisibility", View.VISIBLE)
        config.addIntegration(sut)
        client = PostHog.with(config) as PostHog
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun tap(onUp: () -> Unit = {}) {
        val point = IntArray(2)
        button.getLocationOnScreen(point)
        val time = SystemClock.uptimeMillis()
        val interceptor = activity.window.touchEventInterceptors.last()
        for ((i, action) in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).withIndex()) {
            val event = MotionEvent.obtain(time, time + i * 10, action, point[0] + 20f, point[1] + 20f, 0)
            try {
                interceptor.intercept(event) {
                    assertSame(event, it)
                    if (action == MotionEvent.ACTION_UP) onUp()
                    DispatchState.Consumed
                }
            } finally {
                event.recycle()
            }
        }
    }

    private fun waitForTimeout() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3100))

    @AfterTest
    fun cleanup() {
        client.close()
        shadowOf(Looper.getMainLooper()).idle()
        curtains.close()
        controller.pause().stop().destroy()
    }

    @Test
    fun `detector-only opts work without replay or autocapture and preserve input`() {
        repeat(4) { tap() }
        assertEquals(listOf("\$rageclick"), events.map { it.event })
        waitForTimeout()
        assertEquals(listOf("\$rageclick", "\$dead_click"), events.map { it.event })
        assertTrue(events.all { it.properties?.get("\$event_type") == "touch" })
        assertTrue(events.all { it.properties?.containsKey("\$session_id") == true })
        assertFalse(events.any { it.properties.toString().contains("Action") })
    }

    @Test
    fun `secure windows suppress detectors and clearing the flag allows new taps`() {
        activity.window.addFlags(FLAG_SECURE)
        repeat(4) { tap() }
        waitForTimeout()
        assertTrue(events.isEmpty())
        activity.window.clearFlags(FLAG_SECURE)
        tap()
        waitForTimeout()
        assertEquals(listOf("\$dead_click"), events.map { it.event })
    }

    @Test
    fun `setting secure flag cancels pending dead observation without another touch`() {
        tap()
        activity.window.addFlags(FLAG_SECURE)
        waitForTimeout()
        assertTrue(events.isEmpty())
    }

    @Test
    fun `synchronous native content response suppresses dead detection`() {
        tap { button.text = "Response" }
        waitForTimeout()
        assertTrue(events.isEmpty())
    }

    @Test
    fun `unsupported popup root additions and removals cancel rather than falling through`() {
        for (added in listOf(true, false)) {
            tap()
            roots.toList().forEach { it.onRootViewsChanged(FrameLayout(activity), added) }
            waitForTimeout()
        }
        assertTrue(events.isEmpty())
    }

    @Test
    fun `background navigation optout ABA and close cancel pending observations`() {
        tap()
        sut.onStop(mock())
        waitForTimeout()
        tap()
        client.screen("Next")
        waitForTimeout()
        tap()
        client.optOut()
        client.optIn()
        waitForTimeout()
        repeat(3) { tap() }
        client.reset()
        tap()
        assertTrue(events.isEmpty())
        client.close()
        waitForTimeout()
        assertTrue(events.isEmpty())
        assertTrue(activity.window.touchEventInterceptors.isEmpty())
        assertTrue(roots.isEmpty())
    }

    @Test
    fun `public detector defaults and independent flags gate emissions`() {
        val defaults = PostHogAndroidConfig(API_KEY)
        assertFalse(defaults.captureDeadClicks)
        assertFalse(defaults.captureRageClicks)
        config.captureDeadClicks = false
        repeat(4) { tap() }
        waitForTimeout()
        assertEquals(listOf("\$rageclick"), events.map { it.event })
        config.captureRageClicks = false
        repeat(4) { tap() }
        waitForTimeout()
        assertEquals(1, events.size)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `second client owning global session listener cannot reattribute owner pending taps`() {
        val secondary =
            PostHog.with(
                PostHogAndroidConfig("secondary-test-key").apply {
                    cachePreferences = PostHogMemoryPreferences()
                    remoteConfig = false
                    preloadFeatureFlags = false
                    addBeforeSend { null }
                },
            )
        try {
            repeat(3) { tap() }
            val guard = ReflectionHelpers.getField<com.posthog.PostHogCaptureGuard>(sut, "captureGuard")
            val generation = guard.generation()
            secondary.endSession()
            secondary.startSession()
            // Demonstrate the inherited singleton listener belongs to B, not owner A.
            assertEquals(generation, guard.generation())
            waitForTimeout()
            assertTrue(events.isEmpty())
            tap()
            assertTrue(events.isEmpty())
        } finally {
            secondary.close()
        }
    }
}

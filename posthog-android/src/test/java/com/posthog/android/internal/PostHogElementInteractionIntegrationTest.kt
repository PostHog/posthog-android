package com.posthog.android.internal

import android.app.Activity
import android.app.Dialog
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager.LayoutParams.FLAG_SECURE
import android.widget.Button
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.PostHogFake
import com.posthog.PostHogInterface
import com.posthog.android.API_KEY
import com.posthog.android.PostHogAndroidConfig
import curtains.Curtains
import curtains.DispatchState
import curtains.OnRootViewsChangedListener
import curtains.TouchEventInterceptor
import curtains.touchEventInterceptors
import org.junit.runner.RunWith
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
internal class PostHogElementInteractionIntegrationTest {
    private val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
    private val activity = controller.get()
    private val button =
        Button(activity).also {
            it.isClickable = true
            activity.setContentView(it)
        }
    private val fake = PostHogFake()
    private val installations = mutableListOf<PostHogElementInteractionIntegration>()
    private val rootsListeners = mutableListOf<OnRootViewsChangedListener>()

    // Curtains caches WindowManagerGlobal; Robolectric resets that singleton between tests.
    private val curtains =
        mockStatic(Curtains::class.java).also {
            it.`when`<List<View>> { Curtains.rootViews }.thenReturn(listOf(activity.window.decorView))
            it.`when`<MutableList<OnRootViewsChangedListener>> { Curtains.onRootViewsChangedListeners }.thenReturn(rootsListeners)
            val decor = activity.window.decorView
            decor.measure(
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            )
            decor.layout(0, 0, 400, 800)
        }

    @AfterTest
    fun cleanup() {
        installations.forEach { it.uninstall() }
        shadowOf(Looper.getMainLooper()).idle()
        curtains.close()
        controller.pause().stop().destroy()
    }

    private fun integration(enabled: Boolean = true): PostHogElementInteractionIntegration =
        PostHogElementInteractionIntegration(
            PostHogAndroidConfig(API_KEY).apply {
                captureElementInteractions = enabled
                sessionReplay = false
            },
        ).also { installations += it }

    private fun tap(
        interceptor: TouchEventInterceptor,
        dispatch: (MotionEvent) -> DispatchState = { DispatchState.Consumed },
    ) {
        val position = IntArray(2)
        button.getLocationOnScreen(position)
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { index, action ->
            val event = MotionEvent.obtain(0, index * 10L, action, position[0] + 20f, position[1] + 20f, 0)
            try {
                val originalX = event.x
                val result =
                    interceptor.intercept(event) { forwarded ->
                        assertSame(event, forwarded)
                        assertEquals(originalX, forwarded.x)
                        assertEquals(action, forwarded.actionMasked)
                        dispatch(forwarded)
                    }
                assertSame(DispatchState.Consumed, result)
            } finally {
                event.recycle()
            }
        }
    }

    @Test
    fun `default disabled adds no listener or interceptor`() {
        assertFalse(PostHogAndroidConfig(API_KEY).captureElementInteractions)
        val listeners = Curtains.onRootViewsChangedListeners.size
        val interceptors = activity.window.touchEventInterceptors.size
        integration(false).install(fake)
        assertEquals(listeners, Curtains.onRootViewsChangedListeners.size)
        assertEquals(interceptors, activity.window.touchEventInterceptors.size)
    }

    @Test
    fun `deferred setup captures without replay and dispatches original input exactly once`() {
        val sut = integration()
        sut.install(fake)
        val interceptor = activity.window.touchEventInterceptors.last()
        var dispatches = 0
        tap(interceptor) {
            dispatches++
            DispatchState.Consumed
        }
        assertEquals(2, dispatches)
        assertEquals(1, fake.captures)
        assertEquals("\$autocapture", fake.event)
        assertEquals("touch", fake.properties?.get("\$event_type"))
        assertTrue(fake.properties?.get("\$touch_x") is Float)
        assertTrue(fake.properties?.get("\$touch_y") is Float)
        fake.optedOut = true
        tap(interceptor)
        assertEquals(1, fake.captures)
        sut.uninstall()
        assertFalse(activity.window.touchEventInterceptors.contains(interceptor))
    }

    @Test
    fun `secure windows suppress capture and flags are rechecked after attachment`() {
        activity.window.addFlags(FLAG_SECURE)
        integration().install(fake)
        val interceptor = activity.window.touchEventInterceptors.last()
        var dispatches = 0

        fun dispatch(event: MotionEvent): DispatchState {
            dispatches++
            return DispatchState.Consumed
        }
        tap(interceptor, ::dispatch)
        assertEquals(0, fake.captures)
        activity.window.clearFlags(FLAG_SECURE)
        tap(interceptor, ::dispatch)
        assertEquals(1, fake.captures)
        activity.window.addFlags(FLAG_SECURE)
        tap(interceptor, ::dispatch)
        assertEquals(1, fake.captures)
        assertEquals(6, dispatches)
    }

    @Test
    fun `secure flag during a gesture resets tracking even if cleared before up`() {
        integration().install(fake)
        val interceptor = activity.window.touchEventInterceptors.last()
        var dispatches = 0
        tap(interceptor) { event ->
            dispatches++
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                activity.window.addFlags(FLAG_SECURE)
                val move = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_MOVE }
                try {
                    interceptor.intercept(move) { forwarded ->
                        assertSame(move, forwarded)
                        dispatches++
                        DispatchState.Consumed
                    }
                } finally {
                    move.recycle()
                    activity.window.clearFlags(FLAG_SECURE)
                }
            }
            DispatchState.Consumed
        }
        assertEquals(3, dispatches)
        assertEquals(0, fake.captures)
        tap(interceptor)
        assertEquals(1, fake.captures)
    }

    @Test
    fun `install is idempotent secondary uninstall cannot remove owner and reinstall works`() {
        val initial = activity.window.touchEventInterceptors.size
        val owner = integration()
        owner.install(fake)
        owner.install(fake)
        val secondary = integration()
        secondary.install(PostHogFake())
        secondary.uninstall()
        assertEquals(initial + 1, activity.window.touchEventInterceptors.size)
        owner.uninstall()
        assertEquals(initial, activity.window.touchEventInterceptors.size)
        secondary.install(fake)
        assertEquals(initial + 1, activity.window.touchEventInterceptors.size)
    }

    @Test
    fun `new dialog windows attach and detach without touching other interceptors`() {
        val sut = integration()
        sut.install(fake)
        val dialog = Dialog(activity)
        dialog.setContentView(Button(activity).apply { isClickable = true })
        dialog.show()
        val window = dialog.window!!
        rootsListeners.toList().forEach { it.onRootViewsChanged(window.decorView, true) }
        assertEquals(1, window.touchEventInterceptors.size)
        val sentinel = TouchEventInterceptor { event, dispatch -> dispatch(event) }
        window.touchEventInterceptors += sentinel
        dialog.dismiss()
        rootsListeners.toList().forEach { it.onRootViewsChanged(window.decorView, false) }
        assertEquals(listOf(sentinel), window.touchEventInterceptors)
    }

    @Test
    fun `roots without phoneWindow are skipped without listeners or input interference`() {
        val sut = integration()
        sut.install(fake)
        val installed = activity.window.touchEventInterceptors.toList()
        val listenerCount = rootsListeners.size
        val unsupportedRoot = FrameLayout(activity).apply { layout(0, 0, 200, 200) }
        var appTouches = 0
        val appButton =
            Button(activity).apply {
                isClickable = true
                setOnTouchListener { _, _ ->
                    appTouches++
                    false
                }
            }
        unsupportedRoot.addView(appButton)
        appButton.layout(0, 0, 100, 100)
        rootsListeners.toList().forEach { it.onRootViewsChanged(unsupportedRoot, true) }
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { index, action ->
            val event = MotionEvent.obtain(0, index * 10L, action, 20f, 20f, 0)
            try {
                assertTrue(unsupportedRoot.dispatchTouchEvent(event))
            } finally {
                event.recycle()
            }
        }
        rootsListeners.toList().forEach { it.onRootViewsChanged(unsupportedRoot, false) }
        assertEquals(2, appTouches)
        assertEquals(0, fake.captures)
        assertEquals(installed, activity.window.touchEventInterceptors)
        assertEquals(listenerCount, rootsListeners.size)
        sut.uninstall()
        assertTrue(rootsListeners.isEmpty())
        assertTrue(activity.window.touchEventInterceptors.isEmpty())
    }

    @Test
    fun `SDK failure cannot suppress dispatch and host exceptions are never retried`() {
        val client = mock<PostHogInterface>()
        whenever(client.isOptOut()).thenThrow(IllegalStateException("observation failed"))
        integration().install(client)
        val interceptor = activity.window.touchEventInterceptors.last()
        var calls = 0
        tap(interceptor) {
            calls++
            DispatchState.Consumed
        }
        assertEquals(2, calls)
        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
        val failure = IllegalArgumentException("host failure")
        try {
            assertSame(
                failure,
                assertFailsWith<IllegalArgumentException> {
                    interceptor.intercept(event) {
                        calls++
                        throw failure
                    }
                },
            )
        } finally {
            event.recycle()
        }
        assertEquals(3, calls)
    }

    @Test
    fun `main teardown cancels a queued background installation`() {
        val initial = activity.window.touchEventInterceptors.size
        val sut = integration()
        Thread { sut.install(fake) }.apply {
            start()
            join()
        }
        sut.uninstall()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(initial, activity.window.touchEventInterceptors.size)
        assertTrue(rootsListeners.isEmpty())
    }

    @Test
    fun `main reinstall supersedes queued background teardown`() {
        val initial = activity.window.touchEventInterceptors.size
        val sut = integration()
        sut.install(fake)
        Thread { sut.uninstall() }.apply {
            start()
            join()
        }
        sut.install(fake)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(initial + 1, activity.window.touchEventInterceptors.size)
        tap(activity.window.touchEventInterceptors.last())
        assertEquals(1, fake.captures)
    }

    @Test
    fun `background setup and teardown serialize on main`() {
        val initial = activity.window.touchEventInterceptors.size
        val sut = integration()
        Thread { sut.install(fake) }.apply {
            start()
            join()
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(initial + 1, activity.window.touchEventInterceptors.size)
        Thread { sut.uninstall() }.apply {
            start()
            join()
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(initial, activity.window.touchEventInterceptors.size)
        assertTrue(Curtains.rootViews.isNotEmpty())
    }
}

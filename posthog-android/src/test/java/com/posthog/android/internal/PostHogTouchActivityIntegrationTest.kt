package com.posthog.android.internal

import android.app.Activity
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.android.API_KEY
import com.posthog.android.PostHogAndroidConfig
import com.posthog.android.createPostHogFake
import com.posthog.internal.PostHogDateProvider
import com.posthog.internal.PostHogDeviceDateProvider
import com.posthog.internal.PostHogSessionManager
import curtains.Curtains
import curtains.DispatchState
import curtains.OnRootViewsChangedListener
import curtains.touchEventInterceptors
import org.junit.runner.RunWith
import org.mockito.Mockito.mockStatic
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [26])
internal class PostHogTouchActivityIntegrationTest {
    private val controller = Robolectric.buildActivity(Activity::class.java).setup()
    private val window = controller.get().window
    private val listeners = mutableListOf<OnRootViewsChangedListener>()
    private val curtains =
        mockStatic(Curtains::class.java).also {
            it.`when`<List<View>> { Curtains.rootViews }.thenReturn(listOf(window.decorView))
            it.`when`<MutableList<OnRootViewsChangedListener>> { Curtains.onRootViewsChangedListeners }.thenReturn(listeners)
        }
    private val sut = PostHogTouchActivityIntegration(PostHogAndroidConfig(API_KEY))
    private val wasReactNative = PostHogSessionManager.isReactNative

    @AfterTest
    fun cleanup() {
        sut.uninstall()
        curtains.close()
        controller.pause().stop().destroy()
        PostHogSessionManager.endSession()
        PostHogSessionManager.setDateProvider(PostHogDeviceDateProvider())
        PostHogSessionManager.setAppInBackground(true)
        PostHogSessionManager.isReactNative = wasReactNative
    }

    @Test
    fun `install registers touch hooks and uninstall removes them`() {
        sut.install(createPostHogFake())
        assertEquals(1, window.touchEventInterceptors.size)
        assertEquals(1, listeners.size)
        sut.uninstall()
        assertTrue(listeners.isEmpty())
        assertTrue(window.touchEventInterceptors.isEmpty())
    }

    @Test
    fun `double install is idempotent`() {
        val fake = createPostHogFake()
        sut.install(fake)
        val interceptor = window.touchEventInterceptors.single()
        val listener = listeners.single()
        sut.install(fake)
        assertEquals(listOf(interceptor), window.touchEventInterceptors)
        assertEquals(listOf(listener), listeners)
    }

    @Test
    fun `uninstall after install can be re-installed`() {
        val fake = createPostHogFake()
        sut.install(fake)
        assertEquals(1, window.touchEventInterceptors.size)
        assertEquals(1, listeners.size)
        sut.uninstall()
        assertTrue(window.touchEventInterceptors.isEmpty())
        assertTrue(listeners.isEmpty())
        sut.install(fake)
        assertEquals(1, window.touchEventInterceptors.size)
        assertEquals(1, listeners.size)

        var now = 1_000L
        PostHogSessionManager.isReactNative = false
        PostHogSessionManager.setAppInBackground(false)
        PostHogSessionManager.endSession()
        PostHogSessionManager.setDateProvider(
            object : PostHogDateProvider by PostHogDeviceDateProvider() {
                override fun currentTimeMillis(): Long = now
            },
        )
        PostHogSessionManager.startSession()
        val previous = assertNotNull(PostHogSessionManager.peekSessionId())
        now += 31 * 60 * 1_000L
        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 20f, 20f, 0)
        var dispatches = 0
        try {
            val result =
                window.touchEventInterceptors.single().intercept(event) {
                    dispatches++
                    assertSame(event, it)
                    DispatchState.Consumed
                }
            assertEquals(DispatchState.Consumed, result)
            assertEquals(1, dispatches)
            assertNotEquals(previous, assertNotNull(PostHogSessionManager.peekSessionId()))
        } finally {
            event.recycle()
        }
    }
}

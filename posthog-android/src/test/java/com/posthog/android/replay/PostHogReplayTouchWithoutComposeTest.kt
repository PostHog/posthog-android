package com.posthog.android.replay

import android.app.Activity
import android.graphics.Rect
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.android.PROJECT_TOKEN
import com.posthog.android.PostHogAndroidConfig
import com.posthog.android.createPostHogFake
import com.posthog.android.internal.MainHandler
import com.posthog.internal.PostHogRemoteConfig
import com.posthog.internal.PostHogSessionManager
import curtains.Curtains
import curtains.OnRootViewsChangedListener
import org.junit.runner.RunWith
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Also runs in testWithoutCompose, so it must not reference any Compose type.
@RunWith(AndroidJUnit4::class)
@Config(sdk = [26])
internal class PostHogReplayTouchWithoutComposeTest {
    private val delegate = Executors.newSingleThreadExecutor()

    @BeforeTest
    fun `set up`() {
        PostHogSessionManager.isReactNative = false
        PostHogSessionManager.setAppInBackground(false)
        PostHogSessionManager.endSession()
    }

    @AfterTest
    fun `tear down`() {
        shadowOf(Looper.getMainLooper()).idle()
        PostHogSessionManager.endSession()
        PostHogSessionManager.setAppInBackground(true)
        delegate.shutdownNow()
    }

    @Test
    fun `touches are hit-tested when Compose is absent`() {
        if (java.lang.Boolean.getBoolean("posthog.test.noCompose")) {
            assertFailsWith<ClassNotFoundException> { Class.forName("androidx.compose.ui.geometry.Offset") }
        }
        val submits = AtomicInteger(0)
        val executor =
            object : ExecutorService by delegate {
                override fun submit(task: Runnable): Future<*> {
                    submits.incrementAndGet()
                    return delegate.submit(task)
                }
            }
        val remoteConfig =
            mock<PostHogRemoteConfig> {
                on { isSessionReplayFlagActive() } doReturn true
                on { makeSamplingDecision(any()) } doReturn true
                on { getEventTriggers() } doReturn emptySet()
                on { hasRemoteConfigFetched() } doReturn true
            }
        val config =
            PostHogAndroidConfig(PROJECT_TOKEN).apply {
                remoteConfigHolder = remoteConfig
                sessionReplay = true
            }
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        activity.setContentView(
            FrameLayout(activity).apply {
                addView(View(activity).apply { tag = "ph-no-capture" }, FrameLayout.LayoutParams(100, 100))
            },
        )
        shadowOf(Looper.getMainLooper()).idle()
        val window = activity.window
        val content = Rect().also { window.decorView.findViewById<View>(android.R.id.content).getGlobalVisibleRect(it) }
        val curtains = mockStatic(Curtains::class.java)
        curtains.`when`<List<View>> { Curtains.rootViews }.thenReturn(listOf(window.decorView))
        curtains.`when`<MutableList<OnRootViewsChangedListener>> { Curtains.onRootViewsChangedListeners }
            .thenReturn(mutableListOf())
        val sut = PostHogReplayIntegration(activity, config, MainHandler(), executor)
        val fake = createPostHogFake()
        fake.sessionReplayActive = false
        sut.install(fake)
        try {
            PostHogSessionManager.startSession()
            sut.onSessionIdChanged()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(sut.isActive())

            fun isRecorded(
                x: Float,
                y: Float,
            ): Boolean {
                val before = submits.get()
                val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, content.left + x, content.top + y, 0)
                window.callback.dispatchTouchEvent(event)
                event.recycle()
                delegate.submit {}.get(2, TimeUnit.SECONDS)
                return submits.get() > before
            }

            assertFalse(isRecorded(50f, 50f))
            assertTrue(isRecorded(250f, 300f))
        } finally {
            sut.uninstall()
            curtains.close()
            controller.pause().stop().destroy()
        }
    }
}

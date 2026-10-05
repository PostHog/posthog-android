package com.posthog.android.replay

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.PostHogFake
import com.posthog.PostHogInterface
import com.posthog.android.API_KEY
import com.posthog.android.PostHogAndroidConfig
import com.posthog.android.internal.MainHandler
import com.posthog.android.replay.internal.ViewTreeSnapshotStatus
import com.posthog.internal.PostHogDateProvider
import com.posthog.internal.PostHogRemoteConfig
import com.posthog.internal.PostHogSessionManager
import com.posthog.internal.replay.RRFullSnapshotEvent
import com.posthog.internal.replay.RRWireframe
import curtains.Curtains
import curtains.OnRootViewsChangedListener
import org.junit.runner.RunWith
import org.mockito.MockedStatic
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.util.UUID
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [28], shadows = [PostHogReplaySceneSchedulingTest.ScenePixelCopy::class])
@LooperMode(LooperMode.Mode.PAUSED)
internal class PostHogReplaySceneSchedulingTest {
    private class QueuedExecutor : AbstractExecutorService() {
        val tasks = ArrayDeque<Runnable>()
        private var stopped = false

        override fun execute(command: Runnable) {
            check(!stopped)
            tasks.addLast(command)
        }

        fun runNext() {
            val task = tasks.removeFirst()
            task.run()
            (task as FutureTask<*>).get()
        }

        override fun shutdown() {
            stopped = true
        }

        override fun shutdownNow(): MutableList<Runnable> =
            tasks.toMutableList().also {
                tasks.clear()
                shutdown()
            }

        override fun isShutdown(): Boolean = stopped

        override fun isTerminated(): Boolean = stopped && tasks.isEmpty()

        override fun awaitTermination(
            timeout: Long,
            unit: TimeUnit,
        ): Boolean = isTerminated
    }

    @Implements(PixelCopy::class)
    class ScenePixelCopy {
        companion object {
            var copies = 0
            var onCopy: ((Window) -> Int)? = null

            @JvmStatic
            @Implementation
            fun request(
                window: Window,
                bitmap: Bitmap,
                listener: PixelCopy.OnPixelCopyFinishedListener,
                handler: Handler,
            ) {
                copies++
                bitmap.eraseColor(Color.rgb(copies % 255, 40, 80))
                listener.onPixelCopyFinished(onCopy?.invoke(window) ?: PixelCopy.SUCCESS)
            }
        }
    }

    private val roots = mutableListOf<View>()
    private val rootListeners = mutableListOf<OnRootViewsChangedListener>()
    private val executor = QueuedExecutor()
    private val fake = PostHogFake()
    private val dialogs = mutableListOf<Dialog>()
    private lateinit var controller: ActivityController<Activity>
    private lateinit var curtains: MockedStatic<Curtains>
    private lateinit var sut: PostHogReplayIntegration
    private lateinit var config: PostHogAndroidConfig
    private var consumePostsImmediately = false
    private val activityView: View get() = controller.get().window.decorView
    private val activityStatus: ViewTreeSnapshotStatus get() = assertNotNull(sut.decorViews[activityView])

    @BeforeTest
    fun setUp() {
        ScenePixelCopy.copies = 0
        ScenePixelCopy.onCopy = null
        PostHogSessionManager.isReactNative = false
        PostHogSessionManager.setAppInBackground(false)
        PostHogSessionManager.endSession()
        PostHogSessionManager.startSession()
        curtains = mockStatic(Curtains::class.java)
        curtains.`when`<List<View>> { Curtains.rootViews }.thenReturn(roots)
        curtains.`when`<MutableList<OnRootViewsChangedListener>> { Curtains.onRootViewsChangedListeners }.thenReturn(rootListeners)
        controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        prepareWindow(controller.get().window, WindowManager.LayoutParams.TYPE_BASE_APPLICATION)
        roots.add(activityView)
        config =
            PostHogAndroidConfig(API_KEY).apply {
                sessionReplay = true
                sessionReplayConfig.screenshot = true
                sessionReplayConfig.verifyScreenshotMaskAlignment = true
                sessionReplayConfig.throttleDelayMs = 0
                remoteConfigHolder =
                    mock<PostHogRemoteConfig> {
                        on { isSessionReplayFlagActive() } doReturn true
                        on { hasRemoteConfigFetched() } doReturn true
                        on { makeSamplingDecision(any()) } doReturn true
                        on { getEventTriggers() } doReturn emptySet()
                    }
                dateProvider =
                    mock<PostHogDateProvider> {
                        on { currentTimeMillis() } doAnswer { 1_000L + SystemClock.uptimeMillis() }
                        on { nanoTime() } doAnswer { TimeUnit.MILLISECONDS.toNanos(10_000L + SystemClock.uptimeMillis()) }
                    }
            }
        val realHandler = Handler(Looper.getMainLooper())
        val handler =
            mock<Handler> {
                on { looper } doReturn Looper.getMainLooper()
                on { post(any()) } doAnswer {
                    val runnable = it.getArgument<Runnable>(0)
                    // Model main consuming a worker's post before the worker can continue.
                    if (consumePostsImmediately) {
                        runnable.run()
                        true
                    } else {
                        realHandler.post(runnable)
                    }
                }
                on { postDelayed(any(), any<Long>()) } doAnswer {
                    realHandler.postDelayed(it.getArgument(0), it.getArgument<Long>(1))
                }
            }
        val mainHandler =
            mock<MainHandler> {
                on { this.handler } doReturn handler
                on { mainLooper } doReturn Looper.getMainLooper()
            }
        sut = PostHogReplayIntegration(ApplicationProvider.getApplicationContext(), config, mainHandler, executor)
    }

    @AfterTest
    fun tearDown() {
        consumePostsImmediately = false
        ScenePixelCopy.onCopy = null
        sut.uninstall()
        executor.shutdownNow()
        dialogs.forEach { it.dismiss() }
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
        curtains.close()
        PostHogSessionManager.endSession()
        PostHogSessionManager.setAppInBackground(true)
    }

    private fun prepareWindow(
        window: Window,
        type: Int,
    ) {
        val view = window.decorView
        shadowOf(Looper.getMainLooper()).idle()
        val attachInfo = ReflectionHelpers.getField<Any>(view, "mAttachInfo")
        ReflectionHelpers.setField(attachInfo, "mWindowVisibility", View.VISIBLE)
        (view.layoutParams as WindowManager.LayoutParams).apply {
            this.type = type
            token = Binder()
        }
        view.measure(
            View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 300, 500)
    }

    private fun start() {
        sut.install(
            object : PostHogInterface by fake {
                override fun getSessionId(): UUID? = PostHogSessionManager.getActiveSessionId()
            },
        )
        sut.start(resumeCurrent = true)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun drain() {
        repeat(20) {
            shadowOf(Looper.getMainLooper()).idle()
            if (executor.tasks.isEmpty()) return
            executor.runNext()
        }
        error("Scene scheduling did not settle")
    }

    private fun showDialog(): Dialog {
        val dialog = Dialog(controller.get()).also { dialogs.add(it) }
        dialog.setContentView(View(controller.get()))
        dialog.window!!.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window!!.setDimAmount(0.35f)
        dialog.show()
        prepareWindow(dialog.window!!, WindowManager.LayoutParams.TYPE_APPLICATION)
        val decor = dialog.window!!.decorView
        rootListeners.toList().forEach { it.onRootViewsChanged(decor, true) }
        roots.add(decor)
        shadowOf(Looper.getMainLooper()).idle()
        return dialog
    }

    private fun removeDialog(dialog: Dialog) {
        val decor = dialog.window!!.decorView
        rootListeners.toList().forEach { it.onRootViewsChanged(decor, false) }
        roots.remove(decor)
        dialog.dismiss()
    }

    private fun dirtyViews(): Set<View> = ReflectionHelpers.getField(sut, "sceneDirtyViews")

    @Test
    fun `a delayed draw cannot dirty a removed dialog`() {
        config.sessionReplayConfig.throttleDelayMs = 1000
        start()
        val dialog = showDialog()
        drain()
        val decor = dialog.window!!.decorView
        val status = assertNotNull(sut.decorViews[decor])
        status.listener.onDraw()
        drain()
        status.listener.onDraw()
        removeDialog(dialog)
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        drain()
        assertFalse(decor in dirtyViews())
    }

    @Test
    fun `a failed copy cannot restore a removed dialog to dirty state`() {
        start()
        val dialog = showDialog()
        ScenePixelCopy.onCopy = { window ->
            if (window === dialog.window) {
                removeDialog(dialog)
                PixelCopy.ERROR_SOURCE_INVALID
            } else {
                PixelCopy.SUCCESS
            }
        }
        drain()
        assertFalse(dialog.window!!.decorView in dirtyViews())
    }

    @Test
    fun `a failed copy after stop cannot restore dirty state`() {
        start()
        ScenePixelCopy.onCopy = {
            sut.stop()
            PixelCopy.ERROR_SOURCE_INVALID
        }
        drain()
        assertTrue(dirtyViews().isEmpty())
    }

    @Test
    fun `ordinary layouts share the throttled draw cadence`() {
        config.sessionReplayConfig.throttleDelayMs = 1000
        start()
        drain()
        activityStatus.listener.onDraw()
        drain()
        val copies = ScenePixelCopy.copies
        repeat(9) {
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
            activityStatus.layoutListener!!.onGlobalLayout()
            activityStatus.listener.onDraw()
            drain()
        }
        assertEquals(copies, ScenePixelCopy.copies)
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
        drain()
        assertEquals(copies + 1, ScenePixelCopy.copies)
    }

    @Test
    fun `queued topology is discarded when a dialog appears before the worker starts`() {
        start()
        val dialog = showDialog()
        executor.runNext()
        assertEquals(0, fake.captures)
        drain()
        val snapshot = (fake.properties!!["\$snapshot_data"] as List<*>).filterIsInstance<RRFullSnapshotEvent>().single()

        @Suppress("UNCHECKED_CAST")
        val frames = (snapshot.data as Map<*, *>)["wireframes"] as List<RRWireframe>
        assertEquals(listOf("screenshot", "rectangle", "screenshot"), frames.map { it.type })
        assertTrue(dialog.window!!.decorView in sut.decorViews)
    }

    @Test
    fun `a queued capture cannot adopt a restarted recording generation`() {
        start()
        sut.stop()
        sut.start(resumeCurrent = true)
        shadowOf(Looper.getMainLooper()).idle()
        executor.runNext()
        assertEquals(0, fake.captures)
        drain()
        assertTrue(fake.captures > 0)
    }

    @Test
    fun `follow up survives main consuming the notification before the worker continues`() {
        start()
        drain()
        activityStatus.listener.onDraw()
        ScenePixelCopy.onCopy = {
            ScenePixelCopy.onCopy = null
            activityStatus.listener.onDraw()
            consumePostsImmediately = true
            PixelCopy.SUCCESS
        }
        executor.runNext()
        consumePostsImmediately = false
        assertEquals(1, executor.tasks.size)
        val captures = fake.captures
        drain()
        assertEquals(captures + 1, fake.captures)
    }
}

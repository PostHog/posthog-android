package com.posthog.android.replay

import android.content.Context
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.android.API_KEY
import com.posthog.android.PostHogAndroidConfig
import com.posthog.android.internal.MainHandler
import com.posthog.internal.PostHogLogger
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [26])
internal class PostHogReplayComposeMaskTraversalTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private class RecordingLogger : PostHogLogger {
        val messages = CopyOnWriteArrayList<String>()

        override fun log(message: String) {
            messages.add(message)
        }

        override fun isEnabled(): Boolean = true
    }

    // The production Compose-view heuristic accepts this class name, but it deliberately does not
    // implement Compose's RootForTest contract.
    private class RootForTestMismatchAndroidComposeView(context: Context) : View(context)

    @Test
    fun `compose mask walk is poisoned when compose view is not RootForTest`() {
        val logger = RecordingLogger()
        val walk = runMaskWalk(RootForTestMismatchAndroidComposeView(context), logger)

        assertTrue(
            logger.messages.any { it.startsWith("View is not a RootForTest:") },
            "The real Compose traversal did not reach the RootForTest mismatch: ${logger.messages}",
        )
        assertTrue(
            walk.poisoned,
            "A completed runnable with no RootForTest must poison the walk rather than trust empty masks: ${logger.messages}",
        )
    }

    @Test
    fun `compose mask walk is poisoned when semantics enumeration throws`() {
        val logger = RecordingLogger()
        val composeView = composeViewWithBrokenSemanticsOwner()
        val walk = runMaskWalk(composeView, logger)

        assertTrue(
            logger.messages.any { it.startsWith("Session Replay findMaskableComposeWidgets (main thread) failed:") },
            "The real Compose traversal did not catch the broken semantics owner: ${logger.messages}",
        )
        assertTrue(
            walk.poisoned,
            "A caught semantics traversal exception must poison the walk rather than trust partial masks: ${logger.messages}",
        )
    }

    @Test
    fun `compose mask walk is poisoned when main traversal times out`() {
        val logger = RecordingLogger()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = submitMaskWalk(executor, RootForTestMismatchAndroidComposeView(context), logger)

            // Do not drain the Robolectric main looper: the production one-second await must expire.
            val walk = future.get(2, TimeUnit.SECONDS)

            assertTrue(walk.poisoned, "A timed-out Compose traversal must poison the walk")
        } finally {
            executor.shutdownNow()
            // Remove the traversal runnable left queued by the timeout setup.
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun runMaskWalk(
        view: View,
        logger: RecordingLogger,
    ): PostHogReplayIntegration.MaskWalk {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = submitMaskWalk(executor, view, logger)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (!future.isDone && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.yield()
            }
            return future.get(100, TimeUnit.MILLISECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun submitMaskWalk(
        executor: java.util.concurrent.ExecutorService,
        view: View,
        logger: RecordingLogger,
    ): Future<PostHogReplayIntegration.MaskWalk> {
        val config = PostHogAndroidConfig(API_KEY).apply { this.logger = logger }
        val sut = PostHogReplayIntegration(context, config, MainHandler())
        return executor.submit<PostHogReplayIntegration.MaskWalk> {
            val walk = PostHogReplayIntegration.MaskWalk()
            val method =
                PostHogReplayIntegration::class.java.getDeclaredMethod(
                    "findMaskableWidgets",
                    View::class.java,
                    PostHogReplayIntegration.MaskWalk::class.java,
                    MutableSet::class.java,
                )
            method.isAccessible = true
            method.invoke(sut, view, walk, mutableSetOf<Int>())
            walk
        }
    }

    private fun composeViewWithBrokenSemanticsOwner(): View {
        val composeViewClass = Class.forName(PostHogReplayIntegration.ANDROID_COMPOSE_VIEW_CLASS_NAME)
        val composeView = composeViewClass.getConstructor(Context::class.java).newInstance(context) as View

        // A standalone LayoutNode has no outer semantics wrapper. Installing its owner makes the
        // real getAllSemanticsNodes(true) traversal fail deterministically at current Compose 1.0.0.
        val layoutNodeClass = Class.forName("androidx.compose.ui.node.LayoutNode")
        val layoutNode = layoutNodeClass.getConstructor().newInstance()
        val semanticsOwnerClass = Class.forName("androidx.compose.ui.semantics.SemanticsOwner")
        val brokenOwner = semanticsOwnerClass.getConstructor(layoutNodeClass).newInstance(layoutNode)
        composeViewClass.getDeclaredField("semanticsOwner").apply {
            isAccessible = true
            set(composeView, brokenOwner)
        }
        return composeView
    }
}

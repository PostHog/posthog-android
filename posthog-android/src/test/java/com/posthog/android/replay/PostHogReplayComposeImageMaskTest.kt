package com.posthog.android.replay

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.posthog.android.PROJECT_TOKEN
import com.posthog.android.PostHogAndroidConfig
import com.posthog.android.internal.InteractionActivity
import com.posthog.android.internal.InteractionTestApplication
import com.posthog.android.internal.MainHandler
import com.posthog.android.replay.PostHogMaskModifier.postHogUnmask
import com.posthog.internal.PostHogLogger
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = InteractionTestApplication::class, sdk = [35])
internal class PostHogReplayComposeImageMaskTest {
    @get:Rule
    val compose = createAndroidComposeRule<InteractionActivity>()

    // Stands in for a loaded bitmap or Coil's AsyncImagePainter.
    private class PhotoPainter : Painter() {
        override val intrinsicSize: Size = Size(4f, 4f)

        override fun DrawScope.onDraw() {
            drawRect(Color.Red)
        }
    }

    @Composable
    @Suppress("ktlint:standard:function-naming")
    private fun PhotoImage() {
        Image(painter = PhotoPainter(), contentDescription = null, modifier = Modifier.size(40.dp))
    }

    @Test
    fun `masks a photo image without a content description`() {
        compose.activity.setContent {
            Box(Modifier.testTag("image")) { PhotoImage() }
        }

        val rects = maskRects()

        val expected = compose.onNodeWithTag("image").fetchSemanticsNode().boundsInWindow
        assertEquals(
            listOf(Rect(expected.left.toInt(), expected.top.toInt(), expected.right.toInt(), expected.bottom.toInt())),
            rects,
        )
    }

    @Test
    fun `does not mask a vector image without a content description`() {
        val vector =
            ImageVector.Builder(defaultWidth = 4.dp, defaultHeight = 4.dp, viewportWidth = 4f, viewportHeight = 4f)
                .path(fill = SolidColor(Color.Black)) {
                    moveTo(0f, 0f)
                    lineTo(4f, 0f)
                    lineTo(4f, 4f)
                    close()
                }
                .build()
        compose.activity.setContent {
            Image(painter = rememberVectorPainter(vector), contentDescription = null, modifier = Modifier.size(40.dp))
        }

        assertTrue(maskRects().isEmpty())
    }

    @Test
    fun `does not mask an image without a content description inside postHogUnmask`() {
        compose.activity.setContent {
            Column {
                Box(Modifier.postHogUnmask()) { PhotoImage() }
            }
        }

        assertTrue(maskRects().isEmpty())
    }

    @Test
    fun `does not mask an image without a content description when maskAllImages is off`() {
        compose.activity.setContent { PhotoImage() }

        assertTrue(maskRects(maskAllImages = false).isEmpty())
    }

    private fun maskRects(maskAllImages: Boolean = true): List<Rect> {
        val messages = CopyOnWriteArrayList<String>()
        val config =
            PostHogAndroidConfig(PROJECT_TOKEN).apply {
                logger =
                    object : PostHogLogger {
                        override fun log(message: String) {
                            messages.add(message)
                        }

                        override fun isEnabled(): Boolean = true
                    }
            }
        config.sessionReplayConfig.maskAllImages = maskAllImages
        val sut = PostHogReplayIntegration(compose.activity, config, MainHandler())
        val walk = PostHogReplayIntegration.MaskWalk()
        compose.runOnIdle {
            // On the main thread the Compose traversal runs inline.
            sut.findMaskableWidgets(findComposeView(compose.activity.window.decorView)!!, walk)
        }
        assertFalse(walk.poisoned, "The Compose traversal failed: $messages")
        return walk.rects
    }

    private fun findComposeView(view: View): View? {
        if (view.javaClass.name == PostHogReplayIntegration.ANDROID_COMPOSE_VIEW_CLASS_NAME) {
            return view
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findComposeView(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }
}

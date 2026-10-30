package com.posthog.android.replay

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.DrawModifier
import androidx.compose.ui.draw.paint
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.posthog.android.PROJECT_TOKEN
import com.posthog.android.PostHogAndroidConfig
import com.posthog.android.internal.InteractionActivity
import com.posthog.android.internal.InteractionTestApplication
import com.posthog.android.internal.MainHandler
import com.posthog.android.replay.PostHogMaskModifier.postHogMask
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

    // Shaped like Coil 3's AsyncImage: the element holds no painter, and the node inherits its
    // painter field from a superclass.
    private open class PainterHolderNode(private val painter: Painter) : Modifier.Node(), DrawModifierNode {
        override fun ContentDrawScope.draw() {
            with(painter) { draw(size) }
        }
    }

    private class NodePainterNode(painter: Painter) : PainterHolderNode(painter)

    private class NodePainterElement : ModifierNodeElement<NodePainterNode>() {
        override fun create() = NodePainterNode(PhotoPainter())

        override fun update(node: NodePainterNode) {}

        override fun hashCode() = 0

        override fun equals(other: Any?) = other is NodePainterElement
    }

    private class LegacyPainterModifier(val painter: Painter) : DrawModifier {
        override fun ContentDrawScope.draw() {
            with(painter) { draw(size) }
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

    @Composable
    @Suppress("ktlint:standard:function-naming")
    private fun BitmapImage(contentDescription: String?) {
        Image(
            bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).asImageBitmap(),
            contentDescription = contentDescription,
            modifier = Modifier.size(40.dp),
        )
    }

    @Test
    fun `masks a bitmap image without a content description`() {
        compose.activity.setContent {
            Box(Modifier.testTag("image")) { BitmapImage(contentDescription = null) }
        }

        assertEquals(listOf(boundsOf("image")), maskRects())
    }

    @Test
    fun `masks a bitmap image with a content description`() {
        compose.activity.setContent {
            Box(Modifier.testTag("image")) { BitmapImage(contentDescription = "photo") }
        }

        // Both the semantics pass and the layout pass find it.
        assertEquals(setOf(boundsOf("image")), maskRects().toSet())
    }

    @Test
    fun `does not mask a bitmap image with a content description when maskAllImages is off`() {
        compose.activity.setContent { BitmapImage(contentDescription = "photo") }

        assertTrue(maskRects(maskAllImages = false).isEmpty())
    }

    @Test
    fun `masks an image without a content description inside postHogMask when maskAllImages is off`() {
        compose.activity.setContent {
            Box(Modifier.testTag("image").postHogMask()) { BitmapImage(contentDescription = null) }
        }

        assertEquals(setOf(boundsOf("image")), maskRects(maskAllImages = false).toSet())
    }

    @Test
    fun `masks an image whose painter is only on its modifier node`() {
        compose.activity.setContent {
            Box(Modifier.testTag("image").size(40.dp).then(NodePainterElement()))
        }

        assertEquals(listOf(boundsOf("image")), maskRects())
    }

    @Test
    fun `masks an image whose painter is on an old-style draw modifier`() {
        compose.activity.setContent {
            Box(Modifier.testTag("image").size(40.dp).then(LegacyPainterModifier(PhotoPainter())))
        }

        assertEquals(listOf(boundsOf("image")), maskRects())
    }

    @Test
    fun `masks the whole painted area when padding follows the painter`() {
        compose.activity.setContent {
            Box(Modifier.testTag("image").size(100.dp).paint(PhotoPainter()).padding(30.dp))
        }

        assertEquals(listOf(boundsOf("image")), maskRects())
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
            Box(Modifier.postHogUnmask()) { PhotoImage() }
        }

        assertTrue(maskRects().isEmpty())
    }

    @Test
    fun `does not mask an image without a content description when maskAllImages is off`() {
        compose.activity.setContent { PhotoImage() }

        assertTrue(maskRects(maskAllImages = false).isEmpty())
    }

    private fun boundsOf(tag: String): Rect {
        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
        return Rect(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt())
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

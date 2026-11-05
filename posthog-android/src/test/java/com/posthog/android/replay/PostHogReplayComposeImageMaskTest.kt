package com.posthog.android.replay

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Rect
import android.graphics.Shader
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BuildDrawCacheParams
import androidx.compose.ui.draw.DrawCacheModifier
import androidx.compose.ui.draw.DrawModifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.paint
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.LayoutModifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import org.robolectric.shadows.ShadowDialog
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = InteractionTestApplication::class, sdk = [35])
internal class PostHogReplayComposeImageMaskTest {
    @get:Rule
    val compose = createAndroidComposeRule<InteractionActivity>()

    private val logMessages = CopyOnWriteArrayList<String>()

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

    private fun testBitmap() = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).asImageBitmap()

    @Composable
    @Suppress("ktlint:standard:function-naming")
    private fun BitmapImage(contentDescription: String?) {
        Image(
            bitmap = testBitmap(),
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

    private val vector =
        ImageVector.Builder(defaultWidth = 4.dp, defaultHeight = 4.dp, viewportWidth = 4f, viewportHeight = 4f)
            .path(fill = SolidColor(Color.Black)) {
                moveTo(0f, 0f)
                lineTo(4f, 0f)
                lineTo(4f, 4f)
                close()
            }
            .build()

    private fun Modifier.dismissTarget() =
        semantics(mergeDescendants = true) {
            contentDescription = "Close sheet"
            onClick { true }
        }

    // The activity's content sits below a title bar, so a dialog gives Compose the whole window.
    private fun setWindowContent(content: @Composable () -> Unit) {
        compose.activity.setContent {
            Dialog(
                onDismissRequest = {},
                properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
            ) {
                Box { content() }
            }
        }
        compose.waitForIdle()
    }

    @Composable
    @Suppress("ktlint:standard:function-naming")
    private fun OpenDrawer(content: @Composable () -> Unit) {
        ModalNavigationDrawer(
            drawerState = rememberDrawerState(DrawerValue.Open),
            drawerContent = { ModalDrawerSheet { Text("drawer") } },
            content = content,
        )
    }

    private fun dialogWindow(): View = ShadowDialog.getLatestDialog().window!!.decorView

    private fun windowBounds() = dialogWindow().run { Rect(0, 0, width, height) }

    private fun assertWindowMasked() = assertEquals(listOf(windowBounds()), maskRects(root = dialogWindow()))

    private fun assertNoRectFillsWindow(rects: List<Rect>) = assertTrue(rects.none { it.contains(windowBounds()) })

    @Test
    fun `does not mask a scrim that fills the window`() {
        setWindowContent {
            Canvas(Modifier.fillMaxSize().dismissTarget()) { drawRect(Color.Black) }
        }

        assertTrue(maskRects(root = dialogWindow()).isEmpty())
    }

    @Test
    fun `does not mask the scrim of a modal navigation drawer`() {
        setWindowContent {
            OpenDrawer {}
        }

        val rects = maskRects(root = dialogWindow())

        assertEquals(1, rects.size)
        assertNoRectFillsWindow(rects)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun `does not mask the scrim of a modal bottom sheet`() {
        compose.activity.setContent {
            ModalBottomSheet(onDismissRequest = {}, sheetState = rememberModalBottomSheetState()) { Text("sheet") }
        }
        compose.waitForIdle()

        val rects = maskRects(root = dialogWindow())

        assertTrue(rects.isNotEmpty())
        assertNoRectFillsWindow(rects)
    }

    @Test
    fun `masks a scrim that fills its compose view but not the window`() {
        compose.activity.setContent {
            Box {
                Canvas(Modifier.testTag("scrim").fillMaxSize().dismissTarget()) { drawRect(Color.Black) }
            }
        }

        assertEquals(listOf(boundsOf("scrim")), maskRects())
    }

    @Test
    fun `masks a solid canvas narrower than the window`() {
        setWindowContent {
            Box(Modifier.fillMaxSize()) {
                Canvas(Modifier.testTag("scrim").fillMaxHeight().width(100.dp).dismissTarget()) { drawRect(Color.Black) }
            }
        }

        assertEquals(listOf(boundsOf("scrim")), maskRects(root = dialogWindow()))
    }

    @Test
    fun `masks a solid canvas shorter than the window`() {
        setWindowContent {
            Box(Modifier.fillMaxSize()) {
                Canvas(Modifier.testTag("scrim").fillMaxWidth().height(100.dp).dismissTarget()) { drawRect(Color.Black) }
            }
        }

        assertEquals(listOf(boundsOf("scrim")), maskRects(root = dialogWindow()))
    }

    @Test
    fun `masks a window-filling solid canvas with a role`() {
        setWindowContent {
            Canvas(
                Modifier.fillMaxSize().semantics(mergeDescendants = true) {
                    contentDescription = "Close sheet"
                    role = Role.Image
                    onClick { true }
                },
            ) { drawRect(Color.Black) }
        }

        assertWindowMasked()
    }

    @Test
    fun `masks a window-filling solid box with a bitmap canvas child`() {
        val bitmap = testBitmap()
        setWindowContent {
            Box(Modifier.fillMaxSize().dismissTarget().drawBehind { drawRect(Color.Black) }) {
                Canvas(Modifier.fillMaxSize()) { drawImage(bitmap) }
            }
        }

        assertWindowMasked()
    }

    @Test
    fun `masks a window-filling bitmap canvas with scrim semantics`() {
        val bitmap = testBitmap()
        setWindowContent {
            Canvas(Modifier.fillMaxSize().dismissTarget()) { drawImage(bitmap) }
        }

        assertWindowMasked()
    }

    @Test
    fun `masks a window-filling bitmap canvas behind a closed drawer`() {
        val bitmap = testBitmap()
        setWindowContent {
            ModalNavigationDrawer(
                drawerState = rememberDrawerState(DrawerValue.Closed),
                drawerContent = { ModalDrawerSheet { Text("drawer") } },
            ) {
                Canvas(Modifier.fillMaxSize().dismissTarget()) { drawImage(bitmap) }
            }
        }

        assertTrue(windowBounds() in maskRects(root = dialogWindow()))
    }

    @Test
    fun `masks a window-filling bitmap canvas behind an open drawer`() {
        val bitmap = testBitmap()
        setWindowContent {
            OpenDrawer { Canvas(Modifier.fillMaxSize().dismissTarget()) { drawImage(bitmap) } }
        }

        assertTrue(windowBounds() in maskRects(root = dialogWindow()))
    }

    @Test
    fun `masks a window-filling bitmap canvas inside a pane`() {
        val bitmap = testBitmap()
        setWindowContent {
            Box(Modifier.fillMaxSize().semantics { paneTitle = "Document preview" }) {
                Canvas(Modifier.fillMaxSize().dismissTarget()) { drawImage(bitmap) }
            }
        }

        assertWindowMasked()
    }

    @Test
    fun `masks a window-filling bitmap canvas beside a pane`() {
        val bitmap = testBitmap()
        setWindowContent {
            Canvas(Modifier.fillMaxSize().dismissTarget()) { drawImage(bitmap) }
            Box(Modifier.size(40.dp).semantics { paneTitle = "pane" })
        }

        assertWindowMasked()
    }

    @Test
    fun `masks a window-filling canvas that scales a bitmap`() {
        val bitmap = testBitmap()
        setWindowContent {
            Canvas(Modifier.fillMaxSize().dismissTarget()) {
                drawImage(bitmap, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
            }
        }

        assertWindowMasked()
        assertTrue(logMessages.none { "paintsSolidFill failed" in it }, "$logMessages")
    }

    @Test
    fun `masks a window-filling canvas that paints a bitmap through a shader`() {
        val bitmap = testBitmap()
        setWindowContent {
            Canvas(Modifier.fillMaxSize().dismissTarget()) { drawRect(ShaderBrush(ImageShader(bitmap))) }
        }

        assertWindowMasked()
    }

    @Suppress("DEPRECATION")
    @Test
    fun `masks a window-filling canvas that sets a shader on the framework paint`() {
        val shader = BitmapShader(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        setWindowContent {
            Canvas(Modifier.fillMaxSize().dismissTarget()) {
                drawIntoCanvas {
                    it.drawRect(0f, 0f, size.width, size.height, Paint().apply { asFrameworkPaint().shader = shader })
                }
            }
        }

        assertWindowMasked()
    }

    @Test
    fun `masks a window-filling solid canvas with a bitmap border`() {
        val bitmap = testBitmap()
        setWindowContent {
            Canvas(
                Modifier.fillMaxSize().dismissTarget().border(2.dp, ShaderBrush(ImageShader(bitmap)), RectangleShape),
            ) { drawRect(Color.Black) }
        }

        assertWindowMasked()
    }

    @Test
    fun `masks a window-filling canvas that paints more than one rectangle`() {
        setWindowContent {
            Canvas(Modifier.fillMaxSize().dismissTarget()) {
                drawRect(Color.Black)
                drawRect(Color.White)
            }
        }

        assertWindowMasked()
    }

    @Test
    fun `masks a window-filling canvas that paints a partial rectangle`() {
        setWindowContent {
            Canvas(Modifier.fillMaxSize().dismissTarget()) { drawRect(Color.Black, size = size / 2f) }
        }

        assertWindowMasked()
    }

    @Test
    fun `masks a window-filling canvas that paints nothing`() {
        setWindowContent {
            Canvas(Modifier.fillMaxSize().dismissTarget()) {}
        }

        assertWindowMasked()
    }

    @Test
    fun `masks a window-filling canvas whose draw throws`() {
        setWindowContent {
            Canvas(Modifier.fillMaxSize().dismissTarget()) {
                if (size.width > 0f) error("draw failed")
            }
        }

        assertWindowMasked()
    }

    @Test
    fun `does not mask a scrim with an old-style modifier that does not draw`() {
        val layout =
            object : LayoutModifier {
                override fun MeasureScope.measure(
                    measurable: Measurable,
                    constraints: Constraints,
                ): MeasureResult {
                    val placeable = measurable.measure(constraints)
                    return layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
            }
        setWindowContent {
            Canvas(Modifier.fillMaxSize().then(layout).dismissTarget()) { drawRect(Color.Black) }
        }

        assertNoRectFillsWindow(maskRects(root = dialogWindow()))
        assertTrue(logMessages.none { "paintsSolidFill failed" in it }, "$logMessages")
    }

    @Test
    fun `does not mask a scrim painted by an old-style draw modifier`() {
        val fill =
            object : DrawModifier {
                override fun ContentDrawScope.draw() = drawRect(Color.Black)
            }
        setWindowContent {
            Box(Modifier.fillMaxSize().dismissTarget().then(fill))
        }

        assertNoRectFillsWindow(maskRects(root = dialogWindow()))
    }

    @Test
    fun `masks a window-filling solid draw held in a draw cache`() {
        setWindowContent {
            Box(Modifier.fillMaxSize().dismissTarget().drawWithCache { onDrawBehind { drawRect(Color.Black) } })
        }

        assertWindowMasked()
    }

    @Test
    fun `masks a window-filling solid draw held in an old-style draw cache`() {
        val drawCache =
            object : DrawCacheModifier {
                override fun onBuildCache(params: BuildDrawCacheParams) = Unit

                override fun ContentDrawScope.draw() = drawRect(Color.Black)
            }
        setWindowContent {
            Box(Modifier.fillMaxSize().dismissTarget().then(drawCache))
        }

        assertWindowMasked()
    }

    @Test
    fun `does not build an app draw cache without its content`() {
        var contentDraws = 0
        setWindowContent {
            Box(
                Modifier.fillMaxSize().dismissTarget().drawWithCache {
                    val layer = obtainGraphicsLayer()
                    layer.record { drawContent() }
                    onDrawWithContent { drawLayer(layer) }
                }.drawBehind {
                    contentDraws++
                    drawRect(Color.Black)
                },
            )
        }

        maskRects(root = dialogWindow())
        compose.runOnIdle {
            val view = findComposeView(dialogWindow())!!
            view.draw(android.graphics.Canvas(Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)))
        }

        assertTrue(contentDraws > 0)
    }

    @Test
    fun `does not mask the scrim of a drawer open over a window-filling click target without a description`() {
        setWindowContent {
            OpenDrawer { Box(Modifier.fillMaxSize().clickable {}) }
        }

        assertNoRectFillsWindow(maskRects(root = dialogWindow()))
    }

    @Test
    fun `masks a scrim inside postHogMask`() {
        setWindowContent {
            Canvas(Modifier.fillMaxSize().postHogMask().dismissTarget()) { drawRect(Color.Black) }
        }

        assertWindowMasked()
    }

    @Test
    fun `masks a window-filling canvas with a content description and no click action`() {
        setWindowContent {
            Canvas(Modifier.fillMaxSize().semantics(mergeDescendants = true) { contentDescription = "chart" }) {
                drawRect(Color.Black)
            }
        }

        assertWindowMasked()
    }

    @Test
    fun `masks a window-filling clickable image with a content description`() {
        setWindowContent {
            Image(
                painter = rememberVectorPainter(vector),
                contentDescription = "photo",
                modifier = Modifier.fillMaxSize().clickable {},
            )
        }

        assertWindowMasked()
    }

    @Test
    fun `masks a window-filling clickable icon with a content description`() {
        setWindowContent {
            Icon(vector, contentDescription = "icon", modifier = Modifier.fillMaxSize().clickable {})
        }

        assertWindowMasked()
    }

    @Test
    fun `masks an icon inside a window-filling icon button`() {
        setWindowContent {
            IconButton(onClick = {}, modifier = Modifier.fillMaxSize()) {
                Icon(vector, contentDescription = "icon")
            }
        }

        assertWindowMasked()
    }

    @Test
    fun `masks a vector image merged into a window-filling clickable parent`() {
        setWindowContent {
            Box(Modifier.fillMaxSize().clickable {}) {
                Image(rememberVectorPainter(vector), contentDescription = "photo", modifier = Modifier.size(40.dp))
            }
        }

        assertWindowMasked()
    }

    @Test
    fun `masks a window-filling painted node with scrim semantics`() {
        setWindowContent {
            Box(Modifier.fillMaxSize().paint(PhotoPainter()).dismissTarget())
        }

        assertEquals(setOf(windowBounds()), maskRects(root = dialogWindow()).toSet())
    }

    @Test
    fun `does not mask a clickable image with a content description when maskAllImages is off`() {
        compose.activity.setContent {
            Box(Modifier.clickable {}) { BitmapImage(contentDescription = "photo") }
        }

        assertTrue(maskRects(maskAllImages = false).isEmpty())
    }

    private fun boundsOf(tag: String): Rect {
        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
        return Rect(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt())
    }

    private fun maskRects(
        maskAllImages: Boolean = true,
        root: View = compose.activity.window.decorView,
    ): List<Rect> {
        val config =
            PostHogAndroidConfig(PROJECT_TOKEN).apply {
                logger =
                    object : PostHogLogger {
                        override fun log(message: String) {
                            logMessages.add(message)
                        }

                        override fun isEnabled(): Boolean = true
                    }
            }
        config.sessionReplayConfig.maskAllImages = maskAllImages
        val sut = PostHogReplayIntegration(compose.activity, config, MainHandler())
        val walk = PostHogReplayIntegration.MaskWalk()
        compose.runOnIdle {
            // On the main thread the Compose traversal runs inline.
            sut.findMaskableWidgets(findComposeView(root)!!, walk)
        }
        assertFalse(walk.poisoned, "The Compose traversal failed: $logMessages")
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

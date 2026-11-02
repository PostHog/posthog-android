package com.posthog.android.replay

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.posthog.android.PROJECT_TOKEN
import com.posthog.android.PostHogAndroidConfig
import com.posthog.android.internal.InteractionActivity
import com.posthog.android.internal.InteractionTestApplication
import com.posthog.android.internal.MainHandler
import com.posthog.android.replay.PostHogMaskModifier.postHogMask
import com.posthog.android.replay.PostHogMaskModifier.postHogUnmask
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = InteractionTestApplication::class, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Measures real text widths.
internal class PostHogReplayComposeMaskScopeTest {
    @get:Rule
    val compose = createAndroidComposeRule<InteractionActivity>()

    private val textModifier = Modifier.size(40.dp)

    @Test
    fun `unmasking a child of a clickable row keeps its sibling masked`() {
        compose.activity.setContent {
            Row(Modifier.clickable {}) {
                BasicText("public", textModifier.testTag("public").postHogUnmask())
                BasicText("secret", textModifier.testTag("secret"))
            }
        }

        assertEquals(listOf(boundsOf("secret")), maskRects())
    }

    @Test
    fun `unmasking a clickable row exposes its children`() {
        compose.activity.setContent {
            Row(Modifier.clickable {}.postHogUnmask()) {
                BasicText("first", textModifier)
                BasicText("second", textModifier)
            }
        }

        assertEquals(emptyList(), maskRects())
    }

    @Test
    fun `masking a clickable row covers the row`() {
        compose.activity.setContent {
            Row(Modifier.testTag("row").clickable {}.postHogMask()) {
                BasicText("first", textModifier)
            }
        }

        assertTrue(boundsOf("row") in maskRects(maskAllTextInputs = false))
    }

    @Test
    fun `password inside a clickable row is masked when text masking is off`() {
        compose.activity.setContent {
            Row(Modifier.clickable {}) {
                BasicText("label", textModifier)
                BasicTextField(
                    value = "hunter2",
                    onValueChange = {},
                    modifier = textModifier.testTag("password"),
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        }

        assertEquals(listOf(boundsOf("password")), maskRects(maskAllTextInputs = false))
    }

    @Test
    fun `visible overflow of text is covered by its mask`() {
        compose.activity.setContent {
            Row(Modifier.fillMaxWidth().clickable {}) {
                BasicText(
                    "a secret far wider than its layout box",
                    textModifier.testTag("text"),
                    softWrap = false,
                    overflow = TextOverflow.Visible,
                )
            }
        }

        val bounds = boundsOf("text")
        val mask = maskRects().single()
        assertTrue(mask.contains(bounds))
        assertTrue(mask.right > bounds.right, "expected $mask to extend past $bounds")
    }

    @Test
    fun `postHogMask covers the visible overflow of text`() {
        compose.activity.setContent {
            BasicText(
                "a secret far wider than its layout box",
                textModifier.testTag("text").postHogMask(),
                softWrap = false,
                overflow = TextOverflow.Visible,
            )
        }

        val mask = maskRects(maskAllTextInputs = false).single()
        assertTrue(mask.right > boundsOf("text").right, "expected $mask to extend past the layout box")
    }

    @Test
    fun `visible overflow of scaled text is covered in window coordinates`() {
        val overflowing: @Composable (Modifier) -> Unit = { modifier ->
            BasicText(
                "a secret far wider than its layout box",
                textModifier.then(modifier),
                softWrap = false,
                overflow = TextOverflow.Visible,
            )
        }
        compose.activity.setContent { overflowing(Modifier) }
        val unscaled = maskRects().single()

        compose.activity.setContent {
            overflowing(Modifier.graphicsLayer(scaleX = 2f, transformOrigin = TransformOrigin(0f, 0f)))
        }

        assertTrue(maskRects().single().width() >= 2 * unscaled.width() - 1)
    }

    @Test
    fun `text shadow outside the layout box is covered by its mask`() {
        val shadow = Shadow(offset = Offset(100f, 50f), blurRadius = 10f)
        compose.activity.setContent {
            BasicText("secret", textModifier.testTag("text"), style = TextStyle(shadow = shadow))
        }

        val bounds = boundsOf("text")
        val mask = maskRects().single()
        assertTrue(mask.contains(bounds))
        assertTrue(mask.right >= bounds.right + 110 && mask.bottom >= bounds.bottom + 60, "$mask misses the shadow of $bounds")
    }

    @Test
    fun `span shadow outside the layout box is covered by its mask`() {
        val shadow = Shadow(offset = Offset(-20f, 0f))
        compose.activity.setContent {
            BasicText(
                buildAnnotatedString { withStyle(SpanStyle(shadow = shadow)) { append("secret") } },
                Modifier.padding(start = 50.dp).then(textModifier).testTag("text"),
            )
        }

        assertTrue(maskRects().single().left <= boundsOf("text").left - 20)
    }

    @Test
    fun `clipped text is masked to its layout box`() {
        compose.activity.setContent {
            BasicText(
                "a secret far wider than its layout box",
                textModifier.testTag("text"),
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
        }

        assertEquals(listOf(boundsOf("text")), maskRects())
    }

    @Test
    fun `text with cleared semantics is masked`() {
        compose.activity.setContent {
            BasicText("secret", Modifier.testTag("text").clearAndSetSemantics {}.then(textModifier))
        }

        assertEquals(listOf(boundsOf("text")), maskRects())
    }

    @Test
    fun `cleared container is masked over its text`() {
        compose.activity.setContent {
            Box(Modifier.testTag("box").clearAndSetSemantics {}.then(textModifier)) {
                BasicText("secret")
            }
        }

        assertTrue(boundsOf("box") in maskRects())
    }

    @Test
    fun `cleared semantics stay unmasked when masking is off or unmasked`() {
        compose.activity.setContent {
            BasicText("public", Modifier.clearAndSetSemantics {}.then(textModifier))
        }
        assertEquals(emptyList(), maskRects(maskAllTextInputs = false, maskAllImages = false))

        compose.activity.setContent {
            Box(Modifier.postHogUnmask()) {
                BasicText("public", Modifier.clearAndSetSemantics {}.then(textModifier))
            }
        }
        assertEquals(emptyList(), maskRects())
    }

    private fun boundsOf(tag: String): Rect {
        val bounds = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
        return Rect(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt())
    }

    private fun maskRects(
        maskAllTextInputs: Boolean = true,
        maskAllImages: Boolean = true,
    ): List<Rect> {
        val config = PostHogAndroidConfig(PROJECT_TOKEN)
        config.sessionReplayConfig.maskAllTextInputs = maskAllTextInputs
        config.sessionReplayConfig.maskAllImages = maskAllImages
        val sut = PostHogReplayIntegration(compose.activity, config, MainHandler())
        val walk = PostHogReplayIntegration.MaskWalk()
        compose.runOnIdle {
            sut.findMaskableWidgets(findComposeView(compose.activity.window.decorView)!!, walk)
        }
        assertFalse(walk.poisoned)
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

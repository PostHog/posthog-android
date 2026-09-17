package com.posthog.android.internal

import android.view.View
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.AccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.android.PostHogAutocaptureModifier.PostHogAutocaptureIgnore
import com.posthog.android.replay.PostHogMaskModifier.PostHogReplayMask
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
internal class ComposeInteractionTargetResolverTest {
    private val view = Mockito.mock(View::class.java, Mockito.withSettings().extraInterfaces(RootForTest::class.java))
    private val owner = mock<SemanticsOwner>()
    private val rootConfig = SemanticsConfiguration()
    private val buttonConfig =
        SemanticsConfiguration().apply {
            this[SemanticsActions.OnClick] = AccessibilityAction(null) { true }
            this[SemanticsProperties.Role] = Role.Button
            this[SemanticsProperties.TestTag] = "checkout_button"
        }
    private val childConfig = SemanticsConfiguration()
    private val child = node(3, childConfig)
    private val button = node(2, buttonConfig, listOf(child))
    private val root = node(1, rootConfig, listOf(button))

    init {
        whenever((view as RootForTest).semanticsOwner).thenReturn(owner)
        whenever(owner.unmergedRootSemanticsNode).thenReturn(root)
        whenever(button.parent).thenReturn(root)
        whenever(child.parent).thenReturn(button)
    }

    private fun node(
        id: Int,
        config: SemanticsConfiguration,
        children: List<SemanticsNode> = emptyList(),
    ): SemanticsNode =
        mock<SemanticsNode>().also {
            whenever(it.id).thenReturn(id)
            whenever(it.config).thenReturn(config)
            whenever(it.children).thenReturn(children)
            whenever(it.boundsInWindow).thenReturn(Rect(0f, 0f, 100f, 100f))
        }

    private fun resolve(): InteractionTarget? = ComposeInteractionTargetResolver.resolve(view, 20f, 20f, emptyList())

    @Test
    fun `semantic role test tag and nearest actionable ancestor are captured without labels`() {
        childConfig[SemanticsProperties.ContentDescription] = listOf("Never serialized")
        val target = assertNotNull(resolve())
        assertEquals(2, target.semanticsId)
        assertEquals(InteractionElement("button", "checkout_button"), target.elements.first())
        assertEquals(setOf("tag_name", "attr__id", "nth_child", "nth_of_type"), target.elements.first().properties().keys)
    }

    @Test
    fun `unmerged ignored child blocks actionable parent and dynamic false restores capture`() {
        childConfig[PostHogAutocaptureIgnore] = true
        assertNull(resolve())
        childConfig[PostHogAutocaptureIgnore] = false
        assertNotNull(resolve())
        rootConfig[PostHogAutocaptureIgnore] = true
        assertNull(resolve())
    }

    @Test
    fun `replay mask and password semantics exclude entire subtree`() {
        rootConfig[PostHogReplayMask] = true
        assertNull(resolve())
        rootConfig[PostHogReplayMask] = false
        assertNotNull(resolve())
        childConfig[SemanticsProperties.Password] = Unit
        assertNull(resolve())
    }

    @Test
    fun `Compose exclusion also gates native AndroidView interop`() {
        val native = InteractionTarget(view, elements = listOf(InteractionElement("button", "native_button")))
        assertEquals(native, ComposeInteractionTargetResolver.resolve(view, 20f, 20f, emptyList(), native))
        rootConfig[PostHogAutocaptureIgnore] = true
        assertNull(ComposeInteractionTargetResolver.resolve(view, 20f, 20f, emptyList(), native))
    }

    @Test
    fun `disabled buttons and points outside clipped bounds are not targets`() {
        buttonConfig[SemanticsProperties.Disabled] = Unit
        assertNull(resolve())
        assertNull(ComposeInteractionTargetResolver.resolve(view, 200f, 200f, emptyList()))
    }

    @Test
    fun `topmost semantic sibling wins and bounded traversal fails closed`() {
        val overlay =
            node(
                4,
                SemanticsConfiguration().apply {
                    this[SemanticsActions.OnClick] = AccessibilityAction(null) { true }
                    this[SemanticsProperties.Role] = Role.Switch
                },
            )
        whenever(root.children).thenReturn(listOf(button, overlay))
        whenever(overlay.parent).thenReturn(root)
        assertEquals(4, resolve()!!.semanticsId)
        assertEquals(2, resolve()!!.elements.first().nthChild)
        whenever(root.children).thenReturn(List(MAX_INTERACTION_NODES + 1) { overlay })
        assertNull(resolve())
    }
}

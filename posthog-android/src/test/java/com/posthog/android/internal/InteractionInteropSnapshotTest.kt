package com.posthog.android.internal

import android.graphics.Matrix
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.android.PostHogAutocaptureModifier.PostHogAutocaptureIgnore
import com.posthog.android.replay.PostHogMaskModifier.PostHogReplayMask
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
internal class InteractionInteropSnapshotTest {
    @Test
    fun `Compose exclusions prevent native interop content from being read`() {
        val host = Mockito.mock(Class.forName("androidx.compose.ui.platform.AndroidComposeView")) as ViewGroup
        val holderClass = Class.forName("androidx.compose.ui.viewinterop.AndroidViewHolder")
        val holder = Mockito.mock(holderClass) as ViewGroup
        val layout = Mockito.mock(Class.forName("androidx.compose.ui.node.LayoutNode")) as LayoutInfo
        val text = mock<TextView>()
        val owner = mock<SemanticsOwner>()
        val node = mock<SemanticsNode>()
        val config = SemanticsConfiguration()
        whenever((host as RootForTest).semanticsOwner).thenReturn(owner)
        whenever(owner.unmergedRootSemanticsNode).thenReturn(node)
        whenever(node.config).thenReturn(config)
        whenever(node.children).thenReturn(emptyList())
        whenever(node.boundsInWindow).thenReturn(Rect(0f, 0f, 100f, 100f))
        whenever(node.layoutInfo).thenReturn(layout)
        Mockito.`when`(holderClass.getMethod("getLayoutNode").invoke(holder)).thenReturn(layout)
        whenever(host.childCount).thenReturn(1)
        whenever(host.getChildAt(0)).thenReturn(holder)
        whenever(holder.childCount).thenReturn(1)
        whenever(holder.getChildAt(0)).thenReturn(text)
        for (view in listOf(host, holder, text)) whenever(view.matrix).thenReturn(Matrix())
        whenever(text.text).thenReturn("private content")
        whenever(text.contentDescription).thenReturn("private description")

        for (key in listOf(PostHogAutocaptureIgnore, PostHogReplayMask)) {
            config[key] = true
            val snapshot = InteractionResponseSnapshot()
            val first = assertNotNull(snapshot.take(host))
            verify(text, never()).text
            verify(text, never()).contentDescription
            whenever(text.text).thenReturn("x".repeat(20000))
            assertTrue(first.contentEquals(assertNotNull(snapshot.take(host))))
            config[key] = false
        }
    }
}

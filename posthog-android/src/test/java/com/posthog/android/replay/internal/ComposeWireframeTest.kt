package com.posthog.android.replay.internal

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
internal class ComposeWireframeTest {
    private fun node(
        id: Int = 1,
        bounds: Rect = Rect(0, 0, 200, 100),
        text: String? = null,
        isEditable: Boolean = false,
        isPassword: Boolean = false,
        contentDescription: String? = null,
        role: ComposeRole = ComposeRole.None,
        checked: Boolean? = null,
        maskForced: Boolean = false,
        unmaskForced: Boolean = false,
    ) = ComposeSemanticsNode(
        id = id,
        bounds = bounds,
        text = text,
        isEditable = isEditable,
        isPassword = isPassword,
        contentDescription = contentDescription,
        role = role,
        checked = checked,
        maskForced = maskForced,
        unmaskForced = unmaskForced,
    )

    private fun List<ComposeSemanticsNode>.wireframes(
        maskAllTextInputs: Boolean = true,
        density: Float = 1f,
        ancestorUnmasked: Boolean = false,
    ) = toWireframes(
        hostViewId = HOST_ID,
        parentId = HOST_ID,
        density = density,
        maskAllTextInputs = maskAllTextInputs,
        ancestorUnmasked = ancestorUnmasked,
    )

    @Test
    fun `text node becomes a text wireframe`() {
        val wireframe = listOf(node(text = "Welcome back")).wireframes(maskAllTextInputs = false).single()

        assertEquals("text", wireframe.type)
        assertEquals("Welcome back", wireframe.text)
        assertEquals(HOST_ID, wireframe.parentId)
    }

    @Test
    fun `text is masked by default`() {
        // parity with the View path: maskAllTextInputs replaces the text with same-length asterisks
        val wireframe = listOf(node(text = "Welcome back")).wireframes().single()

        assertEquals("************", wireframe.text)
    }

    @Test
    fun `passwords are masked even when text masking is off`() {
        val wireframe = listOf(node(text = "hunter2", isPassword = true, isEditable = true)).wireframes(maskAllTextInputs = false).single()

        assertEquals("*******", wireframe.value)
    }

    @Test
    fun `postHogMask forces masking when text masking is off`() {
        val wireframe = listOf(node(text = "secret", maskForced = true)).wireframes(maskAllTextInputs = false).single()

        assertEquals("******", wireframe.text)
    }

    @Test
    fun `postHogUnmask wins over the mask config`() {
        val wireframe = listOf(node(text = "public", maskForced = true, unmaskForced = true)).wireframes().single()

        assertEquals("public", wireframe.text)
    }

    @Test
    fun `an unmasked ancestor view unmasks the compose content below it`() {
        val wireframe = listOf(node(text = "public")).wireframes(ancestorUnmasked = true).single()

        assertEquals("public", wireframe.text)
    }

    @Test
    fun `editable text becomes a text input`() {
        val wireframe = listOf(node(text = "me@example.com", isEditable = true)).wireframes(maskAllTextInputs = false).single()

        assertEquals("input", wireframe.type)
        assertEquals("text_area", wireframe.inputType)
        assertEquals("me@example.com", wireframe.value)
        assertNull(wireframe.text)
    }

    @Test
    fun `button role becomes a button input carrying its label`() {
        val wireframe = listOf(node(text = "Sign in", role = ComposeRole.Button)).wireframes(maskAllTextInputs = false).single()

        assertEquals("input", wireframe.type)
        assertEquals("button", wireframe.inputType)
        assertEquals("Sign in", wireframe.value)
    }

    @Test
    fun `toggleable roles carry their checked state`() {
        val checkbox =
            listOf(
                node(text = "Remember me", role = ComposeRole.Checkbox, checked = true),
            ).wireframes(maskAllTextInputs = false).single()
        val switch =
            listOf(
                node(text = "Dark mode", role = ComposeRole.Switch, checked = false),
            ).wireframes(maskAllTextInputs = false).single()
        val radio = listOf(node(role = ComposeRole.RadioButton, checked = true)).wireframes(maskAllTextInputs = false).single()

        assertEquals("checkbox", checkbox.inputType)
        assertEquals(true, checkbox.checked)
        assertEquals("Remember me", checkbox.label)
        assertEquals("toggle", switch.inputType)
        assertEquals(false, switch.checked)
        assertEquals("radio", radio.inputType)
        assertEquals(true, radio.checked)
    }

    @Test
    fun `images become a placeholder because semantics carries no pixels`() {
        val described = listOf(node(contentDescription = "Profile picture")).wireframes().single()
        val roled = listOf(node(role = ComposeRole.Image)).wireframes(maskAllTextInputs = false).single()

        assertEquals("image", described.type)
        assertNull(described.base64)
        assertEquals("image", roled.type)
        assertNull(roled.base64)
    }

    @Test
    fun `nodes without text image or role are skipped`() {
        // layout containers carry no visual information, an empty rectangle would only add noise
        assertTrue(listOf(node()).wireframes().isEmpty())
    }

    @Test
    fun `nodes with empty bounds are skipped`() {
        assertTrue(listOf(node(text = "hi", bounds = Rect(0, 0, 0, 0))).wireframes().isEmpty())
    }

    @Test
    fun `bounds are converted from pixels to density independent values`() {
        val wireframe =
            listOf(node(text = "hi", bounds = Rect(20, 40, 220, 140)))
                .wireframes(density = 2f)
                .single()

        assertEquals(10, wireframe.x)
        assertEquals(20, wireframe.y)
        assertEquals(100, wireframe.width)
        assertEquals(50, wireframe.height)
    }

    @Test
    fun `wireframe ids are stable per semantics id and differ across hosts`() {
        // snapshots are diffed by id only, so the same node must keep its id across frames
        // while two Compose roots in one window must not collide
        assertEquals(composeWireframeId(HOST_ID, 7), composeWireframeId(HOST_ID, 7))
        assertNotEquals(composeWireframeId(HOST_ID, 7), composeWireframeId(HOST_ID, 8))
        assertNotEquals(composeWireframeId(HOST_ID, 7), composeWireframeId(HOST_ID + 1, 7))
    }

    private companion object {
        const val HOST_ID = 42
    }
}

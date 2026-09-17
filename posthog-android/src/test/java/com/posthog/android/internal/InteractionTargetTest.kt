package com.posthog.android.internal

import android.app.Activity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import com.posthog.android.R
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

@RunWith(AndroidJUnit4::class)
internal class InteractionTargetTest {
    private val controller = Robolectric.buildActivity(Activity::class.java).setup()
    private val activity = controller.get()
    private val root =
        FrameLayout(activity).also {
            activity.setContentView(it)
            it.layout(0, 0, 400, 400)
        }
    private val resolver = InteractionTargetResolver(composeAvailable = false)

    @AfterTest
    fun cleanup() {
        controller.pause().stop().destroy()
    }

    private fun resolve(
        x: Float = 20f,
        y: Float = 20f,
    ): InteractionTarget? {
        val location = IntArray(2)
        root.getLocationOnScreen(location)
        return resolver.resolve(root, x + location[0], y + location[1])
    }

    private fun button(): Button =
        Button(activity).also {
            root.addView(it)
            it.layout(0, 0, 100, 100)
            it.setOnClickListener { }
            it.stateListAnimator = null
            it.elevation = 0f
            it.id = android.R.id.button1
        }

    @Test
    fun `web serialization matches immutable golden including escaped quotes`() {
        val elements =
            listOf(
                InteractionElement("button", "checkout\"\\\"\\tail", 2, 1),
                InteractionElement("framelayout", null, 1, 1),
            )
        val expected = javaClass.getResource("/json/interaction-properties.json")!!.readText().trim()
        val actual = Gson().toJson(interactionProperties(elements, 45f, 60f))
        assertEquals(Gson().fromJson(expected, Map::class.java), Gson().fromJson(actual, Map::class.java))
    }

    @Test
    fun `optional Compose runtime can be absent`() {
        if (java.lang.Boolean.getBoolean("posthog.test.noCompose")) {
            assertFailsWith<ClassNotFoundException> { Class.forName("androidx.compose.ui.node.RootForTest") }
        }
        button()
        val location = IntArray(2)
        root.getLocationOnScreen(location)
        assertNotNull(InteractionTargetResolver().resolve(root, location[0] + 20f, location[1] + 20f))
    }

    @Test
    fun `only allowlisted fields are emitted and native fallback needs no Compose`() {
        val button = button()
        button.text = "Not captured"
        button.contentDescription = "Not captured either"
        button.tag = "arbitrary tag"
        val target = assertNotNull(resolve())
        assertSame(button, target.view.get())
        assertEquals(InteractionElement("button", "button1"), target.elements.first())
        val props = interactionProperties(target.elements, 20f, 20f).toString()
        assertFalse(props.contains("captured"))
        assertFalse(props.contains("arbitrary"))
        assertFalse(props.contains("el_text"))
    }

    @Test
    fun `nearest actionable ancestor selected and sibling indices reflect native order`() {
        button()
        val parent =
            FrameLayout(activity).also {
                root.addView(it)
                it.layout(0, 0, 100, 100)
                it.isClickable = true
            }
        val child =
            TextView(activity).also {
                parent.addView(it)
                it.layout(0, 0, 100, 100)
            }
        assertSame(parent, resolve()!!.view.get())
        assertEquals(2, resolve()!!.elements.first().nthChild)
        assertEquals(1, resolve()!!.elements.first().nthOfType)
        child.isClickable = true
        assertSame(child, resolve()!!.view.get())
    }

    @Test
    fun `enabled child remains a target beneath a disabled clickable ancestor`() {
        val child = button()
        root.isClickable = true
        root.isEnabled = false
        assertSame(child, assertNotNull(resolve()).view.get())
        root.setTag(R.id.posthog_autocapture_no_capture, true)
        assertNull(resolve())
        root.setTag(R.id.posthog_autocapture_no_capture, false)
        root.tag = "ph-no-capture"
        assertNull(resolve())
    }

    @Test
    fun `disabled clickable child blocks fallback to enabled ancestor`() {
        val child = button()
        root.isClickable = true
        child.isEnabled = false
        assertNull(resolve())
    }

    @Test
    fun `exclusions are subtree wide and dynamic including replay masking`() {
        val button = button()
        root.setTag(R.id.posthog_autocapture_no_capture, true)
        assertNull(resolve())
        root.setTag(R.id.posthog_autocapture_no_capture, false)
        assertNotNull(resolve())
        button.tag = "ph-no-capture"
        assertNull(resolve())
        button.tag = null
        root.contentDescription = "ph-no-capture"
        assertNull(resolve())
        root.contentDescription = null
        assertNotNull(resolve())
    }

    @Test
    fun `nonactionable disabled hidden and covered targets are not captured`() {
        val button = button()
        button.isEnabled = false
        assertNull(resolve())
        button.isEnabled = true
        button.visibility = View.INVISIBLE
        assertNull(resolve())
        button.visibility = View.VISIBLE
        val overlay = View(activity)
        root.addView(overlay)
        overlay.layout(0, 0, 100, 100)
        assertNull(resolve())
        button.z = 4f
        assertSame(button, resolve()!!.view.get())
    }

    @Test
    fun `scroll translation and parent clipping are accounted for`() {
        val button = button()
        button.translationX = 100f
        assertNull(resolve())
        assertSame(button, resolve(120f)!!.view.get())
        root.scrollTo(100, 0)
        assertSame(button, resolve()!!.view.get())
        root.layout(0, 0, 10, 10)
        assertNull(resolve())
    }

    @Test
    fun `padding clips children but not an actionable parent`() {
        button()
        root.isClickable = true
        root.setPadding(30, 30, 30, 30)
        assertSame(root, resolve()!!.view.get())
        assertEquals("button", resolve(40f, 40f)!!.elements.first().type)
    }

    @Test
    fun `deep trees fail closed`() {
        var parent = root
        repeat(MAX_INTERACTION_DEPTH) {
            val child = FrameLayout(activity)
            parent.addView(child)
            child.layout(0, 0, 100, 100)
            parent = child
        }
        parent.isClickable = true
        assertNull(resolve())
    }

    @Test
    fun `hierarchy and identifier payloads are bounded`() {
        assertEquals(128, InteractionElement("button", "x".repeat(500)).properties()["attr__id"].toString().length)
        assertEquals(20, (interactionProperties(List(100) { InteractionElement("view") }, 20f, 20f)["\$elements"] as List<*>).size)
        repeat(MAX_INTERACTION_NODES + 1) { root.addView(View(activity)) }
        button()
        assertNull(resolve())
    }
}

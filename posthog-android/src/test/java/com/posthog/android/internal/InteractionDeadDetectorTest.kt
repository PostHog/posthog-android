package com.posthog.android.internal

import android.app.Activity
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.android.API_KEY
import com.posthog.android.PostHogAndroidConfig
import com.posthog.android.R
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import java.util.Date
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
internal class InteractionDeadDetectorTest {
    private val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
    private val root = FrameLayout(controller.get())
    private val button =
        Button(controller.get()).apply {
            text = "Action"
            isClickable = true
        }
    private val status = TextView(controller.get()).apply { text = "Before" }
    private val config = PostHogAndroidConfig(API_KEY).apply { captureDeadClicks = true }
    private var generation: Long? = 1
    private var time = 0L
    private val events = mutableListOf<Pair<Map<String, Any>, Date>>()
    private val sut =
        InteractionDeadDetector(
            config,
            MainHandler(),
            { generation },
            { "session" },
            { _, props, date -> events += props to date },
            { time },
        )
    private val position = IntArray(2)

    init {
        root.addView(button)
        root.addView(status)
        controller.get().setContentView(root)
        shadowOf(Looper.getMainLooper()).idle()
        // Robolectric attaches manually laid-out Views with a GONE window by default.
        val attachInfo = org.robolectric.util.ReflectionHelpers.getField<Any>(root, "mAttachInfo")
        org.robolectric.util.ReflectionHelpers.setField(attachInfo, "mWindowVisibility", View.VISIBLE)
        root.layout(0, 0, 300, 300)
        button.layout(0, 0, 100, 100)
        status.layout(0, 150, 100, 200)
        button.getLocationOnScreen(position)
    }

    private fun begin() {
        assertTrue(root.isAttachedToWindow, "attached")
        assertTrue(root.isShown, "shown")
        assertEquals(View.VISIBLE, root.windowVisibility, "window")
        assertNotNull(InteractionResponseSnapshot().take(root), "snapshot")
        val target = assertNotNull(InteractionTargetResolver().resolve(root, position[0] + 20f, position[1] + 20f))
        sut.begin(
            root,
            target,
            1,
            position[0] + 20f,
            position[1] + 20f,
            interactionProperties(target.elements, 20f, 20f) + ("\$session_id" to "session"),
        )
    }

    private fun timeout() {
        repeat(30) {
            time += 100
            sut.checkResponse()
        }
    }

    @AfterTest
    fun cleanup() {
        sut.cancel()
        controller.pause().stop().destroy()
    }

    @Test
    fun `no response emits tap time properties timestamp and only web timeout diagnostics`() {
        begin()
        repeat(29) {
            time += 100
            sut.checkResponse()
        }
        time = 2999
        sut.checkResponse()
        assertTrue(events.isEmpty())
        time = 3000
        sut.checkResponse()
        val (properties, timestamp) = events.single()
        assertEquals("touch", properties["\$event_type"])
        assertEquals(20f, properties["\$touch_x"])
        assertEquals(3000L, properties["\$dead_click_absolute_delay_ms"])
        assertEquals(true, properties["\$dead_click_absolute_timeout"])
        assertEquals(timestamp.time, properties["\$dead_click_event_timestamp"])
        assertFalse(properties.toString().contains("Action"))
        timeout()
        assertEquals(1, events.size)
    }

    @Test
    fun `synchronous content change before first frame suppresses`() {
        begin()
        status.text = "After"
        sut.checkResponse()
        status.text = "Before"
        timeout()
        assertTrue(events.isEmpty())
    }

    @Test
    fun `layout scroll focus and state changes each suppress`() {
        val changes: List<() -> Unit> =
            listOf(
                { status.layout(0, 151, 100, 201) },
                { root.scrollTo(0, 10) },
                { button.translationX = 5f },
                { button.isEnabled = false },
                { button.isSelected = true },
                {
                    button.isFocusableInTouchMode = true
                    button.requestFocus()
                },
            )
        for (change in changes) {
            button.isEnabled = true
            begin()
            change()
            timeout()
            assertTrue(events.isEmpty())
            root.scrollTo(0, 0)
        }
    }

    @Test
    fun `pressed only response does not suppress`() {
        begin()
        button.isPressed = true
        timeout()
        assertEquals(1, events.size)
    }

    @Test
    fun `dynamic exclusion and replay masking cancel pending emission`() {
        begin()
        root.setTag(R.id.posthog_autocapture_no_capture, true)
        timeout()
        root.setTag(R.id.posthog_autocapture_no_capture, false)
        begin()
        button.tag = "ph-no-capture"
        timeout()
        assertTrue(events.isEmpty())
    }

    @Test
    fun `consent identity session and config gating cancel`() {
        begin()
        generation = 2
        timeout()
        generation = 1
        begin()
        generation = null
        timeout()
        generation = 1
        begin()
        config.captureDeadClicks = false
        timeout()
        assertTrue(events.isEmpty())
    }

    @Test
    fun `replacement and cancellation leave at most one pending event`() {
        repeat(100) { begin() }
        timeout()
        assertEquals(1, events.size)
        begin()
        sut.cancel()
        timeout()
        assertEquals(1, events.size)
    }

    @Test
    fun `detachment unsupported custom views excessive content and tree bounds fail closed`() {
        begin()
        root.removeView(button)
        timeout()
        assertTrue(events.isEmpty())
        val snapshot = InteractionResponseSnapshot()
        root.addView(object : View(root.context) {})
        assertNull(snapshot.take(root))
        root.removeAllViews()
        root.addView(status)
        status.text = "x".repeat(16385)
        assertNull(snapshot.take(root))
        status.text = "Before"
        repeat(MAX_INTERACTION_NODES) { root.addView(View(root.context)) }
        assertNull(snapshot.take(root))
    }

    @Test
    fun `masked content is not processed and per-observation digests are unlinkable`() {
        val snapshot = InteractionResponseSnapshot()
        status.setTag(R.id.posthog_autocapture_no_capture, true)
        val before = assertNotNull(snapshot.take(root))
        status.text = "x".repeat(20000)
        assertTrue(before.contentEquals(assertNotNull(snapshot.take(root))))
        assertFalse(before.contentEquals(assertNotNull(InteractionResponseSnapshot().take(root))))
    }

    @Test
    fun `timer checks and cleanup do not emit after cancellation or a stalled main thread`() {
        begin()
        time = 100
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        sut.cancel()
        time = 3000
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertTrue(events.isEmpty())
        begin()
        time = 8000
        sut.checkResponse()
        assertTrue(events.isEmpty())
    }

    @Test
    fun `snapshot work bounds include empty semantic content fields`() {
        val sink = InteractionResponseDigest(ByteArray(16))
        kotlin.test.assertFailsWith<IllegalStateException> {
            repeat(16385) { sink.text("") }
        }
    }
}

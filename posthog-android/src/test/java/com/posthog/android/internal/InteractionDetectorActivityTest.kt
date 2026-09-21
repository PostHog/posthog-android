package com.posthog.android.internal

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.horizontalScrollAxisRange
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.posthog.PostHogEvent
import com.posthog.PostHogInterface
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig
import curtains.Curtains
import curtains.OnRootViewsChangedListener
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = InteractionTestApplication::class, sdk = [35])
internal class InteractionDetectorActivityTest {
    @get:Rule val compose = createAndroidComposeRule<InteractionActivity>()
    private val events = CopyOnWriteArrayList<PostHogEvent>()
    private var client: PostHogInterface? = null
    private var curtainsMock: MockedStatic<Curtains>? = null

    @Suppress("DEPRECATION")
    private fun setup() {
        val config =
            PostHogAndroidConfig("test").apply {
                captureRageClicks = true
                captureDeadClicks = true
                sessionReplay = false
                captureApplicationLifecycleEvents = false
                captureScreenViews = false
                captureDeepLinks = false
                capturePushNotificationOpened = false
                capturePushNotificationSubscriptions = false
                remoteConfig = false
                preloadFeatureFlags = false
                addBeforeSend { event ->
                    events += event
                    null
                }
            }
        compose.runOnIdle {
            val roots = mutableListOf<OnRootViewsChangedListener>()
            curtainsMock =
                Mockito.mockStatic(Curtains::class.java).also { mock ->
                    mock.`when`<List<View>> { Curtains.rootViews }.thenReturn(listOf(compose.activity.window.decorView))
                    mock.`when`<MutableList<OnRootViewsChangedListener>> {
                        Curtains.onRootViewsChangedListeners
                    }.thenReturn(roots)
                }
            // Robolectric leaves attached windows GONE, unlike a resumed device Activity.
            val info = ReflectionHelpers.getField<Any>(compose.activity.window.decorView, "mAttachInfo")
            ReflectionHelpers.setField(info, "mWindowVisibility", View.VISIBLE)
            client = PostHogAndroid.with(compose.activity, config)
        }
        compose.waitForIdle()
    }

    private fun tap(
        tag: String,
        settle: Boolean = true,
    ) {
        val point = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow.center
        compose.runOnIdle {
            val time = SystemClock.uptimeMillis()
            for ((index, action) in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).withIndex()) {
                val event = MotionEvent.obtain(time, time + index * 10, action, point.x, point.y, 0)
                try {
                    compose.activity.window.callback.dispatchTouchEvent(event)
                } finally {
                    event.recycle()
                }
            }
        }
        if (settle) compose.waitForIdle()
    }

    private fun timeout() {
        compose.runOnIdle { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3100)) }
        compose.waitForIdle()
    }

    @After
    fun cleanup() {
        compose.runOnIdle {
            client?.close()
            curtainsMock?.close()
        }
    }

    @Test
    fun `real Compose content response suppresses dead event but no-op ripple does not`() {
        setup()
        tap("compose_responsive")
        compose.onNodeWithText("Compose responses: 1").assertExists()
        timeout()
        assertFalse(events.any { it.event == "\$dead_click" })
        tap("compose_noop")
        timeout()
        val dead = events.filter { it.event == "\$dead_click" }
        assertEquals(1, dead.size)
        assertTrue(dead.single().properties?.get("\$elements_chain").toString().contains("compose_noop"))
        assertFalse(events.any { it.event == "\$autocapture" })
        assertFalse(dead.single().properties.toString().contains("responses"))
    }

    @Test
    fun `real Compose rapid no-op taps emit rage once and ignored subtree emits nothing`() {
        setup()
        repeat(6) { tap("compose_noop", settle = false) }
        assertEquals(1, events.count { it.event == "\$rageclick" })
        repeat(4) { tap("compose_ignored") }
        timeout()
        assertEquals(1, events.count { it.event == "\$rageclick" })
        assertEquals(0, events.count { it.event == "\$dead_click" })
        assertFalse(events.any { it.event == "\$autocapture" })
    }

    @Test
    fun `real Compose semantic state layout and scroll responses each suppress dead taps`() {
        val state = mutableStateOf(0)
        val height = mutableStateOf(60)
        val scroll = mutableStateOf(0f)
        var step = 0
        compose.runOnIdle {
            compose.activity.setContent {
                Column {
                    Button(onClick = {
                        when (step++) {
                            0 -> state.value++
                            1 -> height.value += 10
                            2 -> scroll.value += 10f
                        }
                    }, modifier = Modifier.testTag("semantic_response").height(height.value.dp)) {
                        Text("Action")
                    }
                    Text(
                        "State",
                        Modifier.semantics {
                            selected = state.value % 2 == 1
                            horizontalScrollAxisRange = ScrollAxisRange({ scroll.value }, { 100f })
                        },
                    )
                }
            }
        }
        setup()
        repeat(3) {
            tap("semantic_response")
            timeout()
            assertEquals(0, events.count { it.event == "\$dead_click" })
        }
        tap("semantic_response")
        timeout()
        assertEquals(1, events.count { it.event == "\$dead_click" })
    }
}

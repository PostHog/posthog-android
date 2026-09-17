package com.posthog.android.sample

import android.app.Application
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.posthog.PostHogEvent
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig
import com.posthog.android.PostHogAutocaptureModifier.postHogAutocaptureIgnore
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
@Config(application = Application::class, sdk = [35])
class InteractionActivityTest {
    @get:Rule
    val compose = createAndroidComposeRule<InteractionActivity>()

    @Test
    @Suppress("DEPRECATION")
    fun `real Compose and embedded Views capture through window with replay off`() {
        val events = CopyOnWriteArrayList<PostHogEvent>()
        val config =
            PostHogAndroidConfig("test").apply {
                captureElementInteractions = true
                sessionReplay = false
                captureApplicationLifecycleEvents = false
                captureScreenViews = false
                captureDeepLinks = false
                capturePushNotificationOpened = false
                capturePushNotificationSubscriptions = false
                @Suppress("DEPRECATION") // Test isolation: do not fetch remote configuration.
                remoteConfig = false
                preloadFeatureFlags = false
                addBeforeSend { event ->
                    events += event
                    null
                }
            }
        var client: com.posthog.PostHogInterface? = null
        try {
            compose.runOnIdle {
                client = PostHogAndroid.with(compose.activity, config)
                client!!.screen("Interaction")
            }

            fun tap(
                x: Float,
                y: Float,
            ) {
                compose.runOnIdle {
                    val time = SystemClock.uptimeMillis()
                    for ((index, action) in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).withIndex()) {
                        val event = MotionEvent.obtain(time, time + index * 10, action, x, y, 0)
                        try {
                            compose.activity.window.callback.dispatchTouchEvent(event)
                        } finally {
                            event.recycle()
                        }
                    }
                }
                compose.waitForIdle()
            }

            fun tapCompose(tag: String) {
                val point = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow.center
                tap(point.x, point.y)
            }
            tapCompose("compose_responsive")
            compose.onNodeWithText("Compose responses: 1").assertExists()
            tapCompose("compose_noop")
            tapCompose("compose_ignored")

            fun tapView(id: Int) {
                val view = compose.activity.findViewById<android.view.View>(id)
                val location = IntArray(2)
                compose.runOnIdle { view.getLocationInWindow(location) }
                tap(location[0] + view.width / 2f, location[1] + view.height / 2f)
            }
            tapView(R.id.interaction_view_responsive)
            tapView(R.id.interaction_view_noop)
            tapView(R.id.interaction_view_ignored)
            compose.waitUntil { events.count { it.event == "\$autocapture" } >= 4 }
            val captures = events.filter { it.event == "\$autocapture" }
            assertEquals(4, captures.size)
            assertTrue(captures.all { it.properties?.get("\$event_type") == "touch" })
            assertTrue(captures.all { (it.properties?.get("\$touch_x") as? Number)?.toFloat()?.isFinite() == true })
            assertTrue(captures.all { (it.properties?.get("\$touch_y") as? Number)?.toFloat()?.isFinite() == true })
            val chains = captures.map { it.properties?.get("\$elements_chain").toString() }
            assertTrue(chains.any { it.startsWith("button:") && it.contains("compose_responsive") })
            assertTrue(chains.any { it.contains("compose_noop") })
            assertTrue(chains.any { it.contains("interaction_view_responsive") })
            assertTrue(chains.any { it.contains("interaction_view_noop") })
            assertFalse(chains.any { it.contains("ignored") || it.contains("responses") })
            assertTrue(captures.all { it.properties?.containsKey("\$session_id") == true })
            assertTrue(captures.all { it.properties?.get("\$screen_name") == "Interaction" })
            var nestedClicks = 0
            compose.runOnIdle {
                compose.activity.setContent {
                    Column(Modifier.postHogAutocaptureIgnore()) {
                        AndroidView(factory = { context ->
                            ComposeView(context).apply {
                                setContent {
                                    Button(onClick = { nestedClicks++ }, modifier = Modifier.testTag("nested_target")) {
                                        Text("Nested control")
                                    }
                                }
                            }
                        })
                    }
                }
            }
            compose.waitForIdle()
            tapCompose("nested_target")
            assertEquals(1, nestedClicks)
            assertEquals(4, events.count { it.event == "\$autocapture" })

            var hiddenClicks = 0
            compose.runOnIdle {
                compose.activity.setContent {
                    Column(Modifier.clickable { }) {
                        Button(
                            onClick = { hiddenClicks++ },
                            modifier = Modifier.testTag("hidden_ignored").semantics { invisibleToUser() }.postHogAutocaptureIgnore(),
                        ) { Text("Hidden from accessibility") }
                    }
                }
            }
            compose.waitForIdle()
            tapCompose("hidden_ignored")
            assertEquals(1, hiddenClicks)
            assertEquals(4, events.count { it.event == "\$autocapture" })

            var nativeClicks = 0
            var overlayClicks = 0
            compose.runOnIdle {
                compose.activity.setContent {
                    Box(Modifier.size(160.dp)) {
                        AndroidView(
                            factory = { context ->
                                android.widget.Button(context).apply {
                                    id = R.id.interaction_view_noop
                                    setOnClickListener { nativeClicks++ }
                                }
                            },
                            modifier = Modifier.matchParentSize(),
                        )
                        Button(
                            onClick = { overlayClicks++ },
                            modifier = Modifier.matchParentSize().testTag("compose_overlay"),
                        ) { Text("Top control") }
                    }
                }
            }
            compose.waitForIdle()
            tapCompose("compose_overlay")
            assertEquals(1, overlayClicks)
            assertEquals(0, nativeClicks)
            assertEquals(5, events.count { it.event == "\$autocapture" })
            assertTrue(events.last().properties?.get("\$elements_chain").toString().contains("compose_overlay"))
            assertFalse(events.last().properties?.get("\$elements_chain").toString().contains("interaction_view_noop"))

            var ancestorClicks = 0
            compose.runOnIdle {
                val parent =
                    FrameLayout(compose.activity).apply {
                        id = R.id.interaction_view_responsive
                        setOnClickListener { ancestorClicks++ }
                        addView(
                            ComposeView(context).apply {
                                setContent { Text("Plain content", Modifier.fillMaxSize().testTag("native_child")) }
                            },
                            FrameLayout.LayoutParams(-1, -1),
                        )
                    }
                compose.activity.setContentView(parent)
            }
            compose.waitForIdle()
            tapCompose("native_child")
            assertEquals(1, ancestorClicks)
            assertEquals(6, events.count { it.event == "\$autocapture" })
            assertTrue(events.last().properties?.get("\$elements_chain").toString().startsWith("framelayout:"))
            client!!.optOut()
            tapCompose("native_child")
            assertEquals(6, events.count { it.event == "\$autocapture" })
        } finally {
            compose.runOnIdle { client?.close() }
        }
    }
}

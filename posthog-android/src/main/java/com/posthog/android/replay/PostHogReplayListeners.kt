package com.posthog.android.replay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.VectorDrawable
import android.util.Base64
import android.util.DisplayMetrics
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.widget.ImageView
import android.widget.TextView
import com.posthog.PostHog
import com.posthog.PostHogIntegration
import com.posthog.android.internal.displayMetrics
import com.posthog.android.replay.internal.NextDrawListener
import com.posthog.android.replay.internal.NextDrawListener.Companion.onNextDraw
import com.posthog.internal.PostHogThreadFactory
import com.posthog.internal.RRDomContentLoadedEvent
import com.posthog.internal.RREvent
import com.posthog.internal.RRFullSnapshotEvent
import com.posthog.internal.RRLoadedEvent
import com.posthog.internal.RRMetaEvent
import com.posthog.internal.RRTextStyle
import com.posthog.internal.RRWireframe
import curtains.Curtains
import curtains.OnRootViewsChangedListener
import curtains.OnTouchEventListener
import curtains.onDecorViewReady
import curtains.phoneWindow
import curtains.touchEventInterceptors
import curtains.windowAttachCount
import java.io.ByteArrayOutputStream
import java.util.WeakHashMap
import java.util.concurrent.Executors

public class PostHogReplayListeners(private val context: Context) : PostHogIntegration {

    private val decorViews = WeakHashMap<View, NextDrawListener>()
    private val events = mutableListOf<RREvent>()

    private val executor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("PostHogReplayThread"))

    override fun install() {
        Curtains.onRootViewsChangedListeners += OnRootViewsChangedListener { view, added ->
            if (added) {
                val startTimeMs = System.currentTimeMillis()

                if (events.isEmpty()) {
                    events.add(RRDomContentLoadedEvent(startTimeMs))
                    events.add(RRLoadedEvent(startTimeMs))
                }

                view.phoneWindow?.let { window ->
                    if (view.windowAttachCount == 0) {
                        window.onDecorViewReady { decorView ->
                            val displayMetrics = context.displayMetrics()
                            events.add(
                                RRMetaEvent(
                                    window.attributes.title.toString().substringAfter("/"),
                                    width = displayMetrics.widthPixels,
                                    height = displayMetrics.heightPixels,
                                    startTimeMs,
                                ),
                            )

                            val hasDecorView = decorViews.containsKey(decorView)
                            if (!hasDecorView) {
                                val listener = decorView.onNextDraw {
                                    executor.submit {
                                        generateSnapshot()
                                    }
                                }
                                // TODO: check if WeakHashMap still works since the listener
                                // is still of the decorView and may lose the ability to be destroyed
                                decorViews[decorView] = listener
                            }
                        }

                        window.touchEventInterceptors += OnTouchEventListener { _ ->
                            // TODO: add touch events
                        }
                    }
                }
            } else {
                view.phoneWindow?.let { window ->
                    window.peekDecorView()?.let { decorView ->
                        decorViews[decorView]?.let { listener ->
                            decorView.viewTreeObserver.removeOnDrawListener(listener)
                            decorViews.remove(decorView)
                        }
                    }
                }
                // TODO: flush and start full snapshot?
            }
        }
    }

    private fun generateSnapshot() {
        val currentDecorViews = decorViews.keys
        if (currentDecorViews.isNotEmpty()) {
            val displayMetrics = context.displayMetrics()
            val wireframes = currentDecorViews.mapNotNull {
                convertViewToWireframe(it, displayMetrics)
            }
            if (wireframes.isNotEmpty()) {
                val eventsCopy = events.toMutableList()
                eventsCopy.add(RRFullSnapshotEvent(wireframes, 0, 0))

                val properties = mutableMapOf<String, Any>(
                    "\$snapshot_data" to eventsCopy,
                )
                PostHog.capture("\$snapshot", properties = properties)
            }
        }
    }

    private fun convertViewToWireframe(view: View, displayMetrics: DisplayMetrics): RRWireframe? {
        if (!view.isShown || view.width <= 0 || view.height <= 0) {
            return null
        }
        if (view is ViewStub) {
            return null
        }

        val coordinates = IntArray(2)
        view.getLocationOnScreen(coordinates)
        val x = densityValue(coordinates[0], displayMetrics.density)
        val y = densityValue(coordinates[1], displayMetrics.density)
        val width = densityValue(view.width, displayMetrics.density)
        val height = densityValue(view.height, displayMetrics.density)
        // TODO: background, styles, etc

        var text: String? = null
        var type: String? = null
        var textStyle: RRTextStyle? = null
        // button inherits from textview
        if (view is TextView) {
            text = view.text.toString()
            type = "text"
            val color = String.format("#%06X", (0xFFFFFF and view.currentTextColor))
            textStyle = RRTextStyle(color)
        }

        var base64: String? = null
        if (view is ImageView) {
            type = "image"
            base64 = view.base64()
        }

        val children = mutableListOf<RRWireframe>()
        if (view is ViewGroup && view.childCount > 0) {
            for (i in 0 until view.childCount) {
                val viewChild = view.getChildAt(i) ?: continue
                convertViewToWireframe(viewChild, displayMetrics)?.let {
                    children.add(it)
                }
            }
        }

        return RRWireframe(
            id = view.id,
            x = x,
            y = y,
            width = width,
            height = height,
            text = text,
            type = type,
            textStyle = textStyle,
            childWireframes = children.ifEmpty { null },
            base64 = base64,
        )
    }

    private fun ImageView.base64(): String? {
        if (drawable is BitmapDrawable) {
            val bitmap = (drawable as BitmapDrawable).bitmap
            val byteArrayOutputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 30, byteArrayOutputStream)

            val byteArray = byteArrayOutputStream.toByteArray()
            return Base64.encodeToString(byteArray, Base64.DEFAULT)
        } else if (drawable is VectorDrawable) {
            // TODO: vector drawable
        }
        return null
    }

    private fun densityValue(pixels: Int, density: Float): Int {
        return (pixels / density).toInt()
    }
}

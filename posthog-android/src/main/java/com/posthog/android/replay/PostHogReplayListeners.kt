package com.posthog.android.replay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.VectorDrawable
import android.util.Base64
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
import com.posthog.internal.RRAddedNode
import com.posthog.internal.RREvent
import com.posthog.internal.RRFullSnapshotEvent
import com.posthog.internal.RRIncrementalMutationData
import com.posthog.internal.RRIncrementalSnapshotEvent
import com.posthog.internal.RRMetaEvent
import com.posthog.internal.RRRemovedNode
import com.posthog.internal.RRStyle
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

    private val executor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("PostHogReplayThread"))

    private var lastSnapshots = listOf<RRWireframe>()
    private var sentFullSnapshot = false

    private val displayMetrics by lazy {
        context.displayMetrics()
    }

    override fun install() {
        Curtains.onRootViewsChangedListeners += OnRootViewsChangedListener { view, added ->
            if (added) {
                val startTimeMs = System.currentTimeMillis()

                view.phoneWindow?.let { window ->
                    if (view.windowAttachCount == 0) {
                        window.onDecorViewReady { decorView ->
                            // TODO: only send if the screen change sizes, does the onNextDraw gets called in this case?
                            val event = RRMetaEvent(
                                window.attributes.title.toString().substringAfter("/"),
                                width = displayMetrics.widthPixels,
                                height = displayMetrics.heightPixels,
                                startTimeMs,
                            )
                            captureSnapshot(listOf(event))

                            val hasDecorView = decorViews.containsKey(decorView)
                            if (!hasDecorView) {
                                val timestamp = System.currentTimeMillis()
                                val listener = decorView.onNextDraw {
                                    executor.submit {
                                        generateSnapshot(timestamp)
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

    private fun generateSnapshot(timestamp: Long) {
        val currentDecorViews = decorViews.keys
        if (currentDecorViews.isNotEmpty()) {
            val wireframes = currentDecorViews.mapNotNull {
                convertViewToWireframe(it)
            }

            val events = mutableListOf<RREvent>()

            if (!sentFullSnapshot) {
                val event = RRFullSnapshotEvent(
                    wireframes,
                    initialOffsetTop = 0,
                    initialOffsetLeft = 0,
                    timestamp = timestamp,
                )
                events.add(event)
                sentFullSnapshot = true
            } else {
                // TODO: incremental snapshot
                val (addedItems, removedItems) = findAddedAndRemovedItems(flattenList(lastSnapshots), flattenList(wireframes))

                val addedNodes = mutableListOf<RRAddedNode>()
                addedItems.forEach {
                    val item = RRAddedNode(it, parentId = it.parentId)
                    addedNodes.add(item)
                }

                val removedNodes = mutableListOf<RRRemovedNode>()
                removedItems.forEach {
                    val item = RRRemovedNode(it.id, parentId = it.parentId)
                    removedNodes.add(item)
                }

                val incrementalMutationData = RRIncrementalMutationData(
                    adds = addedNodes.ifEmpty { null },
                    removes = removedNodes.ifEmpty { null },
                )

                if (addedNodes.isNotEmpty() || removedNodes.isNotEmpty()) {
                    val incrementalSnapshotEvent = RRIncrementalSnapshotEvent(
                        mutationData = incrementalMutationData,
                        timestamp = timestamp,
                    )
                    events.add(incrementalSnapshotEvent)
                }
            }

            if (events.isNotEmpty()) {
                captureSnapshot(events)
            }

            lastSnapshots = wireframes
        }
    }

    private fun captureSnapshot(events: List<RREvent>) {
        val properties = mutableMapOf<String, Any>(
            "\$snapshot_data" to events,
        )
        PostHog.capture("\$snapshot", properties = properties)
    }

    private fun convertViewToWireframe(view: View, parentId: Int? = null): RRWireframe? {
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
        val style = RRStyle()
        // TODO: font family, font size,
        view.background?.let { background ->
            background.getColor()?.let { color ->
                style.backgroundColor = color
            }
        }

        var text: String? = null
        var type: String? = null
        // button inherits from textview
        if (view is TextView) {
            // TODO: masking
            text = view.text.toString()
            type = "text"
            style.color = view.currentTextColor.toRRColor()
            // TODO: how to get border details?
            style.borderWidth = 1
            style.borderColor = "#000000ff"
        }

        var base64: String? = null
        if (view is ImageView) {
            type = "image"
            base64 = view.base64()
        }

        val viewId = System.identityHashCode(view)

        val children = mutableListOf<RRWireframe>()
        if (view is ViewGroup && view.childCount > 0) {
            for (i in 0 until view.childCount) {
                val viewChild = view.getChildAt(i) ?: continue
                convertViewToWireframe(viewChild, parentId = viewId)?.let {
                    children.add(it)
                }
            }
        }

        return RRWireframe(
            id = viewId,
            x = x,
            y = y,
            width = width,
            height = height,
            text = text,
            type = type,
            style = style,
            childWireframes = children.ifEmpty { null },
            base64 = base64,
            parentId = parentId,
        )
    }

    private fun Drawable.getColor(): String? {
        if (this is ColorDrawable) {
            return this.color.toRRColor()
        } else if (this is RippleDrawable && numberOfLayers >= 1) {
            try {
                val drawable = getDrawable(0)
                return drawable?.getColor()
            } catch (e: Throwable) {
                // ignore
            }
        } else if (this is InsetDrawable) {
            return drawable?.getColor()
        } else if (this is GradientDrawable) {
            colors?.let { rgcColors ->
                if (rgcColors.isNotEmpty()) {
                    // Get the first color from the array
                    val color = rgcColors[0]

                    // Extract RGB values
                    val red = Color.red(color)
                    val green = Color.green(color)
                    val blue = Color.blue(color)

                    // Construct the RGB color
                    val rgb = Color.rgb(red, green, blue)
                    return rgb.toRRColor()
                }
            }
        }
        return null
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

    private fun Int.toRRColor(): String {
        // TODO: missing alpha
        return String.format("#%06X", (0xFFFFFF and this))
    }

    private fun flattenList(items: List<RRWireframe>): List<RRWireframe> {
        val result = mutableListOf<RRWireframe>()

        for (item in items) {
            result.add(item)

            item.childWireframes?.let {
                result.addAll(flattenList(it))
            }
        }

        return result
    }

    private fun findAddedAndRemovedItems(
        oldItems: List<RRWireframe>,
        newItems: List<RRWireframe>,
    ): Pair<List<RRWireframe>, List<RRWireframe>> {
        // Create HashSet to track unique IDs
        // TODO: should we use System.identityHashCode instead?
        val oldItemIds = HashSet(oldItems.map { it.id })
        val newItemIds = HashSet(newItems.map { it.id })

        // Find added items by subtracting oldItemIds from newItemIds
        val addedIds = newItemIds - oldItemIds
        val addedItems = newItems.filter { it.id in addedIds }

        // Find removed items by subtracting newItemIds from oldItemIds
        val removedIds = oldItemIds - newItemIds
        val removedItems = oldItems.filter { it.id in removedIds }

        return Pair(addedItems, removedItems)
    }
}

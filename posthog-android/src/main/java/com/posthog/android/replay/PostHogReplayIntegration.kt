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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.widget.ImageView
import android.widget.TextView
import com.posthog.PostHog
import com.posthog.PostHogIntegration
import com.posthog.android.PostHogAndroidConfig
import com.posthog.android.internal.displayMetrics
import com.posthog.android.replay.internal.NextDrawListener.Companion.onNextDraw
import com.posthog.android.replay.internal.ViewTreeSnapshotStatus
import com.posthog.internal.PostHogThreadFactory
import com.posthog.internal.RRAddedNode
import com.posthog.internal.RREvent
import com.posthog.internal.RRFullSnapshotEvent
import com.posthog.internal.RRIncrementalMouseInteractionData
import com.posthog.internal.RRIncrementalMouseInteractionEvent
import com.posthog.internal.RRIncrementalMutationData
import com.posthog.internal.RRIncrementalSnapshotEvent
import com.posthog.internal.RRMetaEvent
import com.posthog.internal.RRMouseInteraction
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
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.Executors

public class PostHogReplayIntegration(
    private val context: Context,
    private val config: PostHogAndroidConfig,
) : PostHogIntegration {

    private val decorViews = WeakHashMap<View, ViewTreeSnapshotStatus>()

    private val executor by lazy {
        Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("PostHogReplayThread"))
    }

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var isSessionActive = false

    private val displayMetrics by lazy {
        context.displayMetrics()
    }

    private val isSessionReplayEnabled: Boolean
        get() = config.sessionReplay && isSessionActive

    private val onRootViewsChangedListener = OnRootViewsChangedListener { view, added ->
        if (added) {
            view.phoneWindow?.let { window ->
                if (view.windowAttachCount == 0) {
                    window.onDecorViewReady { decorView ->
                        val listener = decorView.onNextDraw {
                            if (!isSessionReplayEnabled) {
                                return@onNextDraw
                            }
                            val timestamp = System.currentTimeMillis()

                            executor.submit {
                                generateSnapshot(WeakReference(decorView), timestamp)
                            }
                        }

                        val status = ViewTreeSnapshotStatus(listener)
                        decorViews[decorView] = status
                    }

                    window.touchEventInterceptors += onTouchEventListener
                }
            }
        } else {
            view.phoneWindow?.let { window ->
                window.peekDecorView()?.let { decorView ->
                    decorViews[decorView]?.let { status ->
                        cleanSessionState(decorView, status)
                    }
                }
            }
        }
    }

    private val onTouchEventListener = OnTouchEventListener { motionEvent ->
        if (!isSessionReplayEnabled) {
            return@OnTouchEventListener
        }
        val timestamp = System.currentTimeMillis()
        when (motionEvent.action) {
            // TODO: figure out the best way to handle move events, move does not exist
            // mouse does not make sense, touch maybe?
            MotionEvent.ACTION_DOWN -> {
                generateMouseInteractions(timestamp, motionEvent, RRMouseInteraction.MouseDown)
            }
            MotionEvent.ACTION_UP -> {
                generateMouseInteractions(timestamp, motionEvent, RRMouseInteraction.MouseUp)
            }
        }
    }

    private fun generateMouseInteractions(timestamp: Long, motionEvent: MotionEvent, type: RRMouseInteraction) {
        val mouseInteractions = mutableListOf<RRIncrementalMouseInteractionEvent>()
        for (index in 0 until motionEvent.pointerCount) {
            val id = motionEvent.getPointerId(index)
            val absX = motionEvent.getRawXCompat(index)
            val absY = motionEvent.getRawYCompat(index)

            val mouseInteractionData = RRIncrementalMouseInteractionData(
                id = id,
                type = type,
                x = absX.toInt(),
                y = absY.toInt(),
            )
            val mouseInteraction = RRIncrementalMouseInteractionEvent(mouseInteractionData, timestamp)
            mouseInteractions.add(mouseInteraction)
        }

        if (mouseInteractions.isNotEmpty()) {
            // TODO: we can probably batch those
            // if we batch them, we need to be aware that the order of the events matters
            // also because if we send a mouse interaction later, it might be attached to the wrong
            // screen
            mouseInteractions.capture()
        }
    }

    internal fun sessionActive(enabled: Boolean) {
        isSessionActive = enabled
    }

    private fun cleanSessionState(view: View, status: ViewTreeSnapshotStatus) {
        view.viewTreeObserver?.let { viewTreeObserver ->
            if (viewTreeObserver.isAlive) {
                handler.post {
                    viewTreeObserver.removeOnDrawListener(status.listener)
                }
            }
        }
        view.phoneWindow?.let { window ->
            window.touchEventInterceptors -= onTouchEventListener
        }

        decorViews.remove(view)
    }

    override fun install() {
        Curtains.onRootViewsChangedListeners += onRootViewsChangedListener
    }

    override fun uninstall() {
        Curtains.onRootViewsChangedListeners -= onRootViewsChangedListener

        decorViews.entries.forEach {
            cleanSessionState(it.key, it.value)
        }
    }

    private fun generateSnapshot(viewRef: WeakReference<View>, timestamp: Long) {
        val view = viewRef.get() ?: return
        val status = decorViews[view] ?: return
        val wireframe = view.toWireframe() ?: return

        val events = mutableListOf<RREvent>()

        if (!status.sentMetaEvent) {
            val title = view.phoneWindow?.attributes?.title?.toString()?.substringAfter("/") ?: ""
            val metaEvent = RRMetaEvent(
                href = title,
                width = displayMetrics.widthPixels.densityValue(displayMetrics.density),
                height = displayMetrics.heightPixels.densityValue(displayMetrics.density),
                timestamp = timestamp,
            )
            events.add(metaEvent)
            status.sentMetaEvent = true
        }

        if (!status.sentFullSnapshot) {
            val event = RRFullSnapshotEvent(
                listOf(wireframe),
                initialOffsetTop = 0,
                initialOffsetLeft = 0,
                timestamp = timestamp,
            )
            events.add(event)
            status.sentFullSnapshot = true
        } else {
            val lastSnapshot = status.lastSnapshot
            val lastSnapshots = if (lastSnapshot != null) listOf(lastSnapshot) else emptyList()
            val (addedItems, removedItems) = findAddedAndRemovedItems(lastSnapshots.flattenChildren(), listOf(wireframe).flattenChildren())

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
            events.capture()
        }

        status.lastSnapshot = wireframe
    }

    private fun List<RREvent>.capture() {
        val properties = mutableMapOf<String, Any>(
            "\$snapshot_data" to this,
        )
        PostHog.capture("\$snapshot", properties = properties)
    }

    private fun View.toWireframe(parentId: Int? = null): RRWireframe? {
        val view = this
        if (!view.isShown || view.width <= 0 || view.height <= 0) {
            return null
        }
        if (view is ViewStub) {
            return null
        }

        val coordinates = IntArray(2)
        view.getLocationOnScreen(coordinates)
        val x = coordinates[0].densityValue(displayMetrics.density)
        val y = coordinates[1].densityValue(displayMetrics.density)
        val width = view.width.densityValue(displayMetrics.density)
        val height = view.height.densityValue(displayMetrics.density)
        val style = RRStyle()
        // TODO: font family, font size,
        view.background?.let { background ->
            background.toRGBColor()?.let { color ->
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
                viewChild.toWireframe(parentId = viewId)?.let {
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

    private fun Drawable.toRGBColor(): String? {
        if (this is ColorDrawable) {
            return this.color.toRRColor()
        } else if (this is RippleDrawable && numberOfLayers >= 1) {
            try {
                val drawable = getDrawable(0)
                return drawable?.toRGBColor()
            } catch (e: Throwable) {
                // ignore
            }
        } else if (this is InsetDrawable) {
            return drawable?.toRGBColor()
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
            color?.let {
                return it.defaultColor.toRRColor()
            }
        }
        return null
    }

    private fun ImageView.base64(): String? {
        if (drawable is BitmapDrawable) {
            val bitmap = (drawable as BitmapDrawable).bitmap
            ByteArrayOutputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 30, it)
                val byteArray = it.toByteArray()
                return Base64.encodeToString(byteArray, Base64.DEFAULT)
            }
        } else if (drawable is VectorDrawable) {
            // TODO: vector drawable
        }
        return null
    }

    private fun Int.densityValue(density: Float): Int {
        return (this / density).toInt()
    }

    private fun Int.toRRColor(): String {
        // TODO: missing alpha
        return String.format("#%06X", (0xFFFFFF and this))
    }

    private fun List<RRWireframe>.flattenChildren(): List<RRWireframe> {
        val result = mutableListOf<RRWireframe>()

        for (item in this) {
            result.add(item)

            item.childWireframes?.let {
                result.addAll(it.flattenChildren())
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

    private fun MotionEvent.getRawXCompat(index: Int): Float {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getRawX(index)
        } else {
            rawX
        }
    }

    private fun MotionEvent.getRawYCompat(index: Int): Float {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getRawY(index)
        } else {
            rawY
        }
    }
}

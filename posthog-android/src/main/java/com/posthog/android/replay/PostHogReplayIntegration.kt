package com.posthog.android.replay

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import com.posthog.PostHogIntegration
import com.posthog.android.PostHogAndroidConfig
import com.posthog.android.internal.densityValue
import com.posthog.android.internal.displayMetrics
import com.posthog.android.internal.screenSize
import com.posthog.android.replay.internal.LogcatParser
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
import com.posthog.internal.RRPluginEvent
import com.posthog.internal.RRRemovedNode
import com.posthog.internal.RRStyle
import com.posthog.internal.RRWireframe
import com.posthog.internal.capture
import curtains.Curtains
import curtains.OnRootViewsChangedListener
import curtains.OnTouchEventListener
import curtains.onDecorViewReady
import curtains.phoneWindow
import curtains.touchEventInterceptors
import curtains.windowAttachCount
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

    private var logcatThread: Thread? = null

    @Volatile
    private var logcatInProgress = false

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
                    // TODO: can check if user pressed hardware back button (KEYCODE_BACK)
                    // window.keyEventInterceptors
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
        motionEvent.eventTime
        val timestamp = System.currentTimeMillis()
        when (motionEvent.action.and(MotionEvent.ACTION_MASK)) {
            // TODO: figure out the best way to handle move events, move does not exist
            // mouse does not make sense, touch maybe?
            MotionEvent.ACTION_DOWN -> {
                generateMouseInteractions(timestamp, motionEvent, RRMouseInteraction.TouchStart)
            }
            MotionEvent.ACTION_UP -> {
                generateMouseInteractions(timestamp, motionEvent, RRMouseInteraction.TouchEnd)
            }
        }
    }

    private fun generateMouseInteractions(timestamp: Long, motionEvent: MotionEvent, type: RRMouseInteraction) {
        val mouseInteractions = mutableListOf<RRIncrementalMouseInteractionEvent>()
        for (index in 0 until motionEvent.pointerCount) {
            // if the id is 0, BE transformer will set it to the virtual bodyId
            val id = motionEvent.getPointerId(index)
            val absX = motionEvent.getRawXCompat(index).toInt().densityValue(displayMetrics.density)
            val absY = motionEvent.getRawYCompat(index).toInt().densityValue(displayMetrics.density)

            val mouseInteractionData = RRIncrementalMouseInteractionData(
                id = id,
                type = type,
                x = absX,
                y = absY,
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

        if (isSessionReplayEnabled) {
            initLogcatWatcher(Date())
        } else {
            logcatInProgress = false
            logcatThread?.interruptSafely()
        }
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

    private fun Resources.Theme.toRGBColor(): String? {
        val value = TypedValue()
        resolveAttribute(android.R.attr.windowBackground, value, true)
        return if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT &&
            value.type <= TypedValue.TYPE_LAST_COLOR_INT
        ) {
            value.data
        } else {
            null
        }?.toRGBColor()
    }

    private fun generateSnapshot(viewRef: WeakReference<View>, timestamp: Long) {
        val view = viewRef.get() ?: return
        val status = decorViews[view] ?: return

        val wireframe = view.toWireframe() ?: return

        // if the decorView has no backgroundColor, we use the theme color
        if (wireframe.style?.backgroundColor == null || wireframe.style?.backgroundColor == "#ffffff") {
            context.theme?.toRGBColor()?.let {
                wireframe.style?.backgroundColor = it
            }
        }

        val events = mutableListOf<RREvent>()

        if (!status.sentMetaEvent) {
            val title = view.phoneWindow?.attributes?.title?.toString()?.substringAfter("/") ?: ""
            // TODO: cache and compare, if size changes, we send a ViewportResize event

            val screenSizeInfo = view.context.screenSize() ?: return

            val metaEvent = RRMetaEvent(
                href = title,
                width = screenSizeInfo.width,
                height = screenSizeInfo.height,
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
//            status.sentFullSnapshot = true
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

    private fun View.toWireframe(parentId: Int? = null): RRWireframe? {
        val view = this
        if (!view.isShown || view.width <= 0 || view.height <= 0) {
            return null
        }
        if (view is ViewStub) {
            return null
        }
        if (view.id == android.R.id.statusBarBackground || view.id == android.R.id.navigationBarBackground) {
            return null
        }

        val coordinates = IntArray(2)
        view.getLocationOnScreen(coordinates)
        val x = coordinates[0].densityValue(displayMetrics.density)
        val y = coordinates[1].densityValue(displayMetrics.density)
        val width = view.width.densityValue(displayMetrics.density)
        val height = view.height.densityValue(displayMetrics.density)
        val style = RRStyle()
        view.background?.let { background ->
            // TODO: if its not a solid color, we need to do something else
            // probably a gradient, which is a new Drawable and we'd
            // need to handle it it as a new wireframe (image most likely)
            background.toRGBColor()?.let { color ->
                style.backgroundColor = color
            } ?: run {
                style.backgroundColor = "#ffffff"
            }
        }

        var checked: Boolean? = null

        var text: String? = null
        var type: String? = null
        var inputType: String? = null
        var value: String? = null
        // button inherits from textview
        if (view is TextView) {
            text = if (!view.isNoCapture() && !config.maskAllInputs) {
                view.text.toString()
            } else {
                view.text.toString().mask()
            }

            if (text.isEmpty()) {
                text = view.hint.toString().mask()
            }

            type = "text"
            style.color = view.currentTextColor.toRGBColor()
            // TODO: how to get border details?
            if (view is Button && view !is CheckBox && view !is RadioButton && view !is Switch) {
                style.borderWidth = 1
                style.borderColor = "#000000"
                type = "input"
                inputType = "button"
                value = text
                text = null
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                style.fontFamily = view.typeface?.systemFontFamilyName
            } else {
                view.typeface?.let {
                    when (it) {
                        Typeface.DEFAULT -> style.fontFamily = "sans-serif"
                        Typeface.DEFAULT_BOLD -> style.fontFamily = "sans-serif-bold"
                        Typeface.MONOSPACE -> style.fontFamily = "monospace"
                        Typeface.SERIF -> style.fontFamily = "serif"
                    }
                }
            }
            style.fontSize = view.textSize.toInt().densityValue(displayMetrics.density)
            when (view.textAlignment) {
                View.TEXT_ALIGNMENT_CENTER -> {
                    style.verticalAlign = "center"
                    style.horizontalAlign = "center"
                }
                View.TEXT_ALIGNMENT_TEXT_END, View.TEXT_ALIGNMENT_VIEW_END -> {
                    style.verticalAlign = "center"
                    style.horizontalAlign = "right"
                }
                View.TEXT_ALIGNMENT_TEXT_START, View.TEXT_ALIGNMENT_VIEW_START -> {
                    style.verticalAlign = "center"
                    style.horizontalAlign = "left"
                }
                View.TEXT_ALIGNMENT_GRAVITY -> {
                    val horizontalAlignment = when (view.gravity.and(Gravity.HORIZONTAL_GRAVITY_MASK)) {
                        Gravity.START, Gravity.LEFT -> "left"
                        Gravity.END, Gravity.RIGHT -> "right"
                        Gravity.CENTER, Gravity.CENTER_HORIZONTAL -> "center"
                        else -> "left"
                    }
                    style.horizontalAlign = horizontalAlignment

                    val verticalAlignment = when (view.gravity.and(Gravity.VERTICAL_GRAVITY_MASK)) {
                        Gravity.TOP -> "top"
                        Gravity.BOTTOM -> "bottom"
                        Gravity.CENTER_VERTICAL, Gravity.CENTER -> "center"
                        else -> "center"
                    }
                    style.verticalAlign = verticalAlignment
                }
                else -> {
                    style.verticalAlign = "center"
                    style.horizontalAlign = "left"
                }
            }

            style.paddingTop = view.totalPaddingTop.densityValue(displayMetrics.density)
            style.paddingBottom = view.totalPaddingBottom.densityValue(displayMetrics.density)
            style.paddingLeft = view.totalPaddingLeft.densityValue(displayMetrics.density)
            style.paddingRight = view.totalPaddingRight.densityValue(displayMetrics.density)

            // TODO: the 4 possible drawables
            // view.compoundDrawables
        }

        var label: String? = null
        if (view is CheckBox) {
//            TODO: buttonDrawable
//            view.buttonDrawable
            type = "input"
            inputType = "checkbox"
            label = text
            text = null
            checked = view.isChecked
        }
        if (view is RadioGroup) {
            type = "radio_group"
        }
        if (view is RadioButton) {
            type = "input"
            inputType = "radio"
            label = text
            text = null
            checked = view.isChecked
        }

        if (view is EditText) {
            type = "input"
            inputType = "text_area"
            value = text
            text = null
        }
        var options: List<String>? = null
        if (view is Spinner) {
            type = "input"
            inputType = "select"
            view.selectedItem?.let {
                val maskedValue = it.toString().mask()
                value = maskedValue
                options = listOf(maskedValue)
            }
        }

        var base64: String? = null
        if (view is ImageView) {
            type = "image"
            if (!view.isNoCapture()) {
                // TODO: we can probably do a LRU caching here
                base64 = view.drawable?.base64()
            }
        }

        var max: Int? = null
        var progress: Int? = null
        if (view is ProgressBar) {
            inputType = "progress"
            type = "input"
            val bar = if (view.isIndeterminate) {
                "circular"
            } else {
                max = view.max
                progress = view.progress
                "horizontal"
            }
            style.bar = bar
        }

        if (view is Switch) {
            type = "input"
            inputType = "toggle"
            checked = view.isChecked
            label = text
            text = null
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
            disabled = !view.isEnabled,
            checked = checked,
            inputType = inputType,
            value = value,
            label = label,
            options = options,
            progress = progress,
            max = max,
        )
    }

    private fun Drawable.toRGBColor(): String? {
        if (this is ColorDrawable) {
            return this.color.toRGBColor()
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
                    return rgb.toRGBColor()
                }
            }
            color?.let {
                return it.defaultColor.toRGBColor()
            }
        }
        return null
    }

    private fun Bitmap.isValid(): Boolean {
        return !this.isRecycled &&
            this.width > 0 &&
            this.height > 0
    }

    private fun Drawable.base64(): String? {
        if (this is BitmapDrawable) {
            if (!bitmap.isValid()) {
                return null
            }

            ByteArrayOutputStream(bitmap.allocationByteCount).use {
                // TODO: or webp?
                bitmap.compress(Bitmap.CompressFormat.PNG, 30, it)
                val byteArray = it.toByteArray()
                return Base64.encodeToString(byteArray, Base64.DEFAULT)
            }
        } else if (this is LayerDrawable) {
            if (numberOfLayers > 0) {
                getDrawable(0)?.let {
                    return it.base64()
                }
            }
        } else if (this is InsetDrawable) {
            drawable?.let {
                return it.base64()
            }
        }
        // TODO: vector, gradient drawables
        return null
    }

    private fun Int.toRGBColor(): String {
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

    private fun View.isNoCapture(): Boolean {
        return (this.tag as? String)?.lowercase()?.contains("ph-no-capture") == true
    }

    private fun initLogcatWatcher(date: Date) {
        if (!config.captureLogcat) {
            return
        }
        // TODO: check if its API 23 or higher
        val cmd = mutableListOf("logcat", "-v", "threadtime", "*:E")
        val sdf = SimpleDateFormat("MM-dd HH:mm:ss.mmm", Locale.ROOT)
        cmd.add("-T")
        cmd.add(sdf.format(date.time))

        logcatInProgress = false
        logcatThread?.interruptSafely()
        logcatThread = Thread {
            var process: Process? = null
            try {
                process = Runtime.getRuntime().exec(cmd.toTypedArray())
                process.inputStream.bufferedReader().use {
                    var line: String? = null
                    logcatInProgress = true
                    do {
                        try {
                            line = it.readLine()

                            if (line.isNullOrEmpty()) {
                                continue
                            }
                            // TODO: filter out all non useful stuff
                            if (line.contains("PostHog") || line.contains("StrictMode")) {
                                continue
                            } else {
                                val log = LogcatParser().parse(line) ?: continue

                                val props = mutableMapOf<String, Any>()
                                props["level"] = log.level.toString()
                                val tag = log.tag?.trim() ?: ""
                                val content = log.text?.trim() ?: ""
                                props["payload"] = listOf("$tag: $content")
                                val time = log.time?.time?.time ?: System.currentTimeMillis()
                                val event = RRPluginEvent("rrweb/console@1", props, time)
                                // TODO: batch events
                                listOf(event).capture()
                            }
                        } catch (e: Throwable) {
                            // ignore
                        }
                    } while (line != null && logcatInProgress)
                }
            } catch (e: Throwable) {
                // ignore
            } finally {
                process?.destroy()
            }
        }
        logcatThread?.start()
    }

    private fun Thread.interruptSafely() {
        try {
            interrupt()
        } catch (e: Throwable) {
            // ignore
        }
    }

    private fun String.mask(): String {
        return "*".repeat(this.length)
    }
}

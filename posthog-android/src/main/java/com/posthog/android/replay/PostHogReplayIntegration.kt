package com.posthog.android.replay

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.VectorDrawable
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.WebView
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.RatingBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.posthog.PostHogIntegration
import com.posthog.PostHogInterface
import com.posthog.android.PostHogAndroidConfig
import com.posthog.android.internal.MainHandler
import com.posthog.android.internal.densityValue
import com.posthog.android.internal.displayMetrics
import com.posthog.android.internal.screenSize
import com.posthog.android.internal.webpBase64
import com.posthog.android.replay.PostHogMaskModifier.PostHogReplayMask
import com.posthog.android.replay.internal.NextDrawListener.Companion.onNextDraw
import com.posthog.android.replay.internal.ViewTreeSnapshotStatus
import com.posthog.android.replay.internal.isAliveAndAttachedToWindow
import com.posthog.internal.PostHogThreadFactory
import com.posthog.internal.replay.PostHogSessionReplayHandler
import com.posthog.internal.replay.RRCustomEvent
import com.posthog.internal.replay.RREvent
import com.posthog.internal.replay.RRFullSnapshotEvent
import com.posthog.internal.replay.RRIncrementalMouseInteractionData
import com.posthog.internal.replay.RRIncrementalMouseInteractionEvent
import com.posthog.internal.replay.RRIncrementalMutationData
import com.posthog.internal.replay.RRIncrementalSnapshotEvent
import com.posthog.internal.replay.RRMetaEvent
import com.posthog.internal.replay.RRMouseInteraction
import com.posthog.internal.replay.RRMutatedNode
import com.posthog.internal.replay.RRRemovedNode
import com.posthog.internal.replay.RRStyle
import com.posthog.internal.replay.RRWireframe
import com.posthog.internal.replay.capture
import curtains.Curtains
import curtains.OnRootViewsChangedListener
import curtains.TouchEventInterceptor
import curtains.onDecorViewReady
import curtains.phoneWindow
import curtains.touchEventInterceptors
import curtains.windowAttachCount
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

public class PostHogReplayIntegration(
    private val context: Context,
    private val config: PostHogAndroidConfig,
    private val mainHandler: MainHandler,
) : PostHogIntegration, PostHogSessionReplayHandler {
    private val decorViews = WeakHashMap<View, ViewTreeSnapshotStatus>()

    private val passwordInputTypes =
        listOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        )

    private val executor by lazy {
        Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("PostHogReplayThread"))
    }

    private val displayMetrics by lazy {
        context.displayMetrics()
    }

    private val paint =
        Paint().apply {
            color = Color.BLACK
        }

    @Volatile
    private var isSessionReplayActive: Boolean = false

    // flutter captures snapshots, so we don't need to capture them here
    private val isNativeSdk: Boolean
        get() = (config.sdkName != "posthog-flutter")

    private var postHog: PostHogInterface? = null

    @Volatile
    private var isOnDrawnCalled: Boolean = false

    private fun onDrawCallback() {
        isOnDrawnCalled = true
    }

    private fun addView(
        view: View,
        added: Boolean = true,
    ) {
        try {
            view.phoneWindow?.let { window ->
                var hasDecorView = false

                // react native already has the window attached
                // so we check if the decor view exists otherwise we need the onDecorViewReady anyways
                window.peekDecorView()?.let { decorView ->
                    hasDecorView = decorViews[decorView] != null
                }
                if (added) {
                    if (view.windowAttachCount == 0 || !hasDecorView) {
                        window.onDecorViewReady { decorView ->
                            try {
                                val listener =
                                    decorView.onNextDraw(
                                        mainHandler,
                                        config.dateProvider,
                                        config.sessionReplayConfig.throttleDelayMs,
                                        ::onDrawCallback,
                                    ) {
                                        if (!isActive() || !isNativeSdk) {
                                            return@onNextDraw
                                        }
                                        val timestamp = config.dateProvider.currentTimeMillis()

                                        executor.submit {
                                            try {
                                                generateSnapshot(WeakReference(decorView), WeakReference(window), timestamp)
                                            } catch (e: Throwable) {
                                                config.logger.log("Session Replay generateSnapshot failed: $e.")
                                            }
                                        }
                                    }

                                val status = ViewTreeSnapshotStatus(listener)
                                decorViews[decorView] = status
                            } catch (e: Throwable) {
                                config.logger.log("Session Replay onDecorViewReady failed: $e.")
                            }
                        }

                        window.touchEventInterceptors += onTouchEventListener
                        // TODO: can check if user pressed hardware back button (KEYCODE_BACK)
                        // window.keyEventInterceptors
                    } else {
                        config.logger.log("Session Replay already has onDecorViewReady.")
                    }
                } else {
                    window.peekDecorView()?.let { decorView ->
                        decorViews[decorView]?.let { status ->
                            clearViewListeners(decorView, status)
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            config.logger.log("Session Replay OnRootViewsChangedListener failed: $e.")
        }
    }

    private val onRootViewsChangedListener =
        OnRootViewsChangedListener { view, added ->
            addView(view, added)
        }

    private fun detectKeyboardVisibility(
        view: View,
        visible: Boolean,
    ): Pair<Boolean, RRCustomEvent?> {
        val insets = ViewCompat.getRootWindowInsets(view) ?: return Pair(visible, null)
        val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
        if (visible == imeVisible) {
            return Pair(visible, null)
        }

        val payload = mutableMapOf<String, Any>()
        if (imeVisible) {
            val imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            payload["open"] = true
            payload["height"] = imeHeight.densityValue(displayMetrics.density)
        } else {
            payload["open"] = false
        }

        val event =
            RRCustomEvent(
                tag = "keyboard",
                payload = payload,
                config.dateProvider.currentTimeMillis(),
            )

        return Pair(imeVisible, event)
    }

    private val onTouchEventListener =
        TouchEventInterceptor { motionEvent, dispatch ->
            val timestamp = config.dateProvider.currentTimeMillis()

            try {
                val state = dispatch(motionEvent)

                executor.submit {
                    try {
                        if (!isActive()) {
                            return@submit
                        }
                        when (motionEvent.action.and(MotionEvent.ACTION_MASK)) {
                            MotionEvent.ACTION_DOWN -> {
                                generateMouseInteractions(timestamp, motionEvent, RRMouseInteraction.TouchStart)
                            }
                            MotionEvent.ACTION_UP -> {
                                generateMouseInteractions(timestamp, motionEvent, RRMouseInteraction.TouchEnd)
                            }
                        }
                    } catch (e: Throwable) {
                        config.logger.log("Executor#OnTouchEventListener $motionEvent failed: $e.")
                    }
                }
                state
            } catch (e: Throwable) {
                config.logger.log("TouchEventInterceptor $motionEvent failed: $e.")
                throw e
            }
        }

    private fun generateMouseInteractions(
        timestamp: Long,
        motionEvent: MotionEvent,
        type: RRMouseInteraction,
    ) {
        val mouseInteractions = mutableListOf<RRIncrementalMouseInteractionEvent>()
        for (index in 0 until motionEvent.pointerCount) {
            try {
                // if the id is 0, BE transformer will set it to the virtual bodyId
                val id = motionEvent.getPointerId(index)
                val absX = motionEvent.getRawXCompat(index).toInt().densityValue(displayMetrics.density)
                val absY = motionEvent.getRawYCompat(index).toInt().densityValue(displayMetrics.density)

                val mouseInteractionData =
                    RRIncrementalMouseInteractionData(
                        id = id,
                        type = type,
                        x = absX,
                        y = absY,
                    )
                val mouseInteraction = RRIncrementalMouseInteractionEvent(mouseInteractionData, timestamp)
                mouseInteractions.add(mouseInteraction)
            } catch (e: Throwable) {
                config.logger.log("Reading MotionEvent pointers failed: $e.")
            }
        }

        if (mouseInteractions.isNotEmpty()) {
            // TODO: we can probably batch those
            // if we batch them, we need to be aware that the order of the events matters
            // also because if we send a mouse interaction later, it might be attached to the wrong
            // screen
            mouseInteractions.capture(postHog)
        }
    }

    private fun resetViewSnapshotStates(status: ViewTreeSnapshotStatus) {
        status.sentFullSnapshot = false
        status.sentMetaEvent = false
        status.keyboardVisible = false
        status.lastSnapshot = null
    }

    private fun clearViewListeners(
        view: View,
        status: ViewTreeSnapshotStatus,
    ) {
        if (view.isAliveAndAttachedToWindow()) {
            mainHandler.handler.post {
                // 2nd check to avoid:
                // Exception java.lang.IllegalStateException: This ViewTreeObserver is not alive
                // Since the post might be executed a bit later if the thread is busy
                if (view.isAliveAndAttachedToWindow()) {
                    try {
                        // swallow the exception because we still wanna remove it from the decorViews
                        view.viewTreeObserver?.removeOnDrawListener(status.listener)
                    } catch (e: Throwable) {
                        config.logger.log("Removing the viewTreeObserver failed: $e.")
                    }
                }
            }
        }

        view.phoneWindow?.let { window ->
            window.touchEventInterceptors -= onTouchEventListener
        }

        decorViews.remove(view)
    }

    override fun install(postHog: PostHogInterface) {
        if (integrationInstalled || !isSupported()) {
            return
        }
        integrationInstalled = true
        this.postHog = postHog

        // workaround for react native that is started after the window is added
        // Curtains.rootViews should be empty for normal apps yet
        Curtains.rootViews.forEach { view ->
            addView(view)
        }

        try {
            Curtains.onRootViewsChangedListeners += onRootViewsChangedListener
        } catch (e: Throwable) {
            config.logger.log("Session Replay setup failed: $e.")
        }
    }

    override fun uninstall() {
        try {
            integrationInstalled = false
            this.postHog = null
            Curtains.onRootViewsChangedListeners -= onRootViewsChangedListener

            decorViews.entries.forEach {
                clearViewListeners(it.key, it.value)
            }

            isSessionReplayActive = false
            isOnDrawnCalled = false

            // clear to help GC
            clearSnapshotStates()
            decorViews.clear()
        } catch (e: Throwable) {
            config.logger.log("Session Replay uninstall failed: $e.")
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

    private fun generateSnapshot(
        viewRef: WeakReference<View>,
        windowRef: WeakReference<Window>,
        timestamp: Long,
    ) {
        val view = viewRef.get() ?: return
        val status = decorViews[view] ?: return
        val window = windowRef.get() ?: return

        val wireframe =
            if (config.sessionReplayConfig.screenshot) {
                view.toScreenshotWireframe(
                    window,
                ) ?: return
            } else {
                view.toWireframe() ?: return
            }

        // if the decorView has no backgroundColor, we use the theme color
        // no need to do this if we are capturing a screenshot
        if (wireframe.style?.backgroundColor == null && !config.sessionReplayConfig.screenshot) {
            context.theme?.toRGBColor()?.let {
                wireframe.style?.backgroundColor = it
            }
        }

        val events = mutableListOf<RREvent>()

        if (!status.sentMetaEvent) {
            val title = view.phoneWindow?.attributes?.title?.toString()?.substringAfter("/") ?: ""
            // TODO: cache and compare, if size changes, we send a ViewportResize event

            val screenSizeInfo = view.context.screenSize() ?: return

            val metaEvent =
                RRMetaEvent(
                    href = title,
                    width = screenSizeInfo.width,
                    height = screenSizeInfo.height,
                    timestamp = timestamp,
                )
            events.add(metaEvent)
            status.sentMetaEvent = true
        }

        if (!status.sentFullSnapshot) {
            val event =
                RRFullSnapshotEvent(
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
            val (addedItems, removedItems, updatedItems) =
                findAddedAndRemovedItems(
                    lastSnapshots.flattenChildren(),
                    listOf(wireframe).flattenChildren(),
                )

            val addedNodes = mutableListOf<RRMutatedNode>()
            addedItems.forEach {
                val item = RRMutatedNode(it, parentId = it.parentId)
                addedNodes.add(item)
            }

            val removedNodes = mutableListOf<RRRemovedNode>()
            removedItems.forEach {
                val item = RRRemovedNode(it.id, parentId = it.parentId)
                removedNodes.add(item)
            }

            val updatedNodes = mutableListOf<RRMutatedNode>()
            updatedItems.forEach {
                val item = RRMutatedNode(it, parentId = it.parentId)
                updatedNodes.add(item)
            }

            if (addedNodes.isNotEmpty() || removedNodes.isNotEmpty() || updatedNodes.isNotEmpty()) {
                val incrementalMutationData =
                    RRIncrementalMutationData(
                        adds = addedNodes.ifEmpty { null },
                        removes = removedNodes.ifEmpty { null },
                        updates = updatedNodes.ifEmpty { null },
                    )

                val incrementalSnapshotEvent =
                    RRIncrementalSnapshotEvent(
                        mutationData = incrementalMutationData,
                        timestamp = timestamp,
                    )
                events.add(incrementalSnapshotEvent)
            }
        }

        // detect keyboard visibility
        val (visible, event) = detectKeyboardVisibility(view, status.keyboardVisible)
        status.keyboardVisible = visible
        event?.let {
            events.add(it)
        }

        if (events.isNotEmpty()) {
            events.capture(postHog)
        }

        status.lastSnapshot = wireframe
    }

    /**
     * Adapted from https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/core/java/android/view/View.java;l=11620;bpv=0;bpt=1
     */
    private fun View.isVisible(): Boolean {
        try {
            if (isAttachedToWindow) {
                // Attached to invisible window means this view is not visible.
                if (windowVisibility != View.VISIBLE) {
                    return false
                }
                // An invisible predecessor or one with alpha zero means
                // that this view is not visible to the user.
                var current: Any? = this
                while (current is View) {
                    val view = current
                    val transitionAlpha = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) view.transitionAlpha else 1f
                    // We have attach info so this view is attached and there is no
                    // need to check whether we reach to ViewRootImpl on the way up.
                    if (view.alpha <= 0 || transitionAlpha <= 0 || view.visibility != View.VISIBLE) {
                        return false
                    }
                    current = view.parent
                }
                // Check if the view is entirely covered by its predecessors.
                val visibleRect = Rect()
                val offset = Point()

                return getGlobalVisibleRect(visibleRect, offset)

                // TODO: also check for getGlobalVisibleRect intersects the display
//            if (boundInView != null) {
//                visibleRect.offset(-offset.x, -offset.y)
//                return boundInView.intersect(visibleRect)
//            }
            }
        } catch (e: Throwable) {
            config.logger.log("Session Replay isVisible failed: $e.")
            // if there's an exception, we just return true otherwise we might miss some views
            return true
        }
        return false
    }

    private fun Drawable.shouldMaskDrawable(): Boolean {
        return when (this) {
            is InsetDrawable, is ColorDrawable, is VectorDrawable, is GradientDrawable, is LayerDrawable -> false
            // otherwise its not accessible anyway
            is BitmapDrawable -> !bitmap.isRecycled
            else -> true
        }
    }

    private fun View.globalVisibleRect(): Rect {
        val rect = Rect()
        getGlobalVisibleRect(rect)
        return rect
    }

    private fun View.isTextInputSensitive(): Boolean {
        return isNoCapture(config.sessionReplayConfig.maskAllTextInputs)
    }

    private fun View.isAnyInputSensitive(): Boolean {
        return this.isTextInputSensitive() || config.sessionReplayConfig.maskAllImages
    }

    private fun TextView.shouldMaskTextView(): Boolean {
        // inputType is 0-based
        return this.isTextInputSensitive() || passwordInputTypes.contains(inputType - 1)
    }

    private fun findMaskableWidgets(
        view: View,
        maskableWidgets: MutableList<Rect>,
    ): Boolean {
        when {
            view.isComposeView() -> {
                findMaskableComposeWidgets(view, maskableWidgets)
            }

            view.isNoCapture() -> {
                val rect = view.globalVisibleRect()
                maskableWidgets.add(rect)
            }

            view is TextView -> {
                val viewText = view.text?.toString()
                var maskIt = false
                if (!viewText.isNullOrEmpty()) {
                    maskIt =
                        view.shouldMaskTextView()
                }

                val hint = view.hint?.toString()
                if (!maskIt && !hint.isNullOrEmpty()) {
                    maskIt =
                        view.shouldMaskTextView()
                }

                if (maskIt) {
                    val rect = view.globalVisibleRect()
                    maskableWidgets.add(rect)
                }
            }

            view is Spinner -> {
                if (view.shouldMaskSpinner()) {
                    val rect = view.globalVisibleRect()
                    maskableWidgets.add(rect)
                }
            }

            view is ImageView -> {
                if (view.shouldMaskImage()) {
                    val rect = view.globalVisibleRect()
                    maskableWidgets.add(rect)
                }
            }

            view is WebView -> {
                if (view.isAnyInputSensitive()) {
                    val rect = view.globalVisibleRect()
                    maskableWidgets.add(rect)
                }
            }

            view is ViewGroup && view.childCount > 0 -> {
                for (i in 0 until view.childCount) {
                    if (isOnDrawnCalled) {
                        config.logger.log("Session Replay screenshot discarded due to screen changes.")
                        return false
                    }

                    val viewChild = view.getChildAt(i) ?: continue

                    if (!viewChild.isVisible()) {
                        continue
                    }

                    if (!findMaskableWidgets(viewChild, maskableWidgets)) {
                        // do not continue if the screen has changed
                        return false
                    }
                }
            }
        }
        return true
    }

    private fun findMaskableComposeWidgets(
        view: View,
        maskableWidgets: MutableList<Rect>,
    ) {
        val latch = CountDownLatch(1)

        // compose requires the handler to be on the main thread
        // see https://github.com/PostHog/posthog-android/issues/203
        mainHandler.handler.post {
            try {
                val semanticsOwner =
                    (view as? RootForTest)?.semanticsOwner ?: run {
                        config.logger.log("View is not a RootForTest: $view")
                        return@post
                    }
                val semanticsNodes = semanticsOwner.getAllSemanticsNodes(true)

                semanticsNodes.forEach { node ->
                    val hasText = node.config.contains(SemanticsProperties.Text)
                    val hasEditableText = node.config.contains(SemanticsProperties.EditableText)
                    val hasPassword = node.config.contains(SemanticsProperties.Password)
                    val hasImage = node.config.contains(SemanticsProperties.ContentDescription)

                    val hasMaskModifier = node.config.contains(PostHogReplayMask)
                    val isNoCapture = hasMaskModifier && node.config[PostHogReplayMask]

                    when {
                        isNoCapture -> {
                            maskableWidgets.add(node.boundsInWindow.toRect())
                        }

                        !hasMaskModifier -> {
                            when {
                                (hasText || hasEditableText) && (config.sessionReplayConfig.maskAllTextInputs || hasPassword) -> {
                                    maskableWidgets.add(node.boundsInWindow.toRect())
                                }

                                hasImage && config.sessionReplayConfig.maskAllImages -> {
                                    maskableWidgets.add(node.boundsInWindow.toRect())
                                }
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                // swallow possible errors due to compose versioning, etc
                config.logger.log("Session Replay findMaskableComposeWidgets (main thread) failed: $e")
            } finally {
                latch.countDown()
            }
        }

        try {
            // await for 1s max
            latch.await(1000, TimeUnit.MILLISECONDS)
        } catch (e: Throwable) {
            config.logger.log("Session Replay findMaskableComposeWidgets failed: $e")
        }
    }

    private fun androidx.compose.ui.geometry.Rect.toRect(): Rect {
        return Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
    }

    private fun View.isComposeView(): Boolean {
        return isComposeAvailable && this.javaClass.name.contains(ANDROID_COMPOSE_VIEW)
    }

    private val isComposeAvailable by lazy(LazyThreadSafetyMode.PUBLICATION) {
        try {
            Class.forName(ANDROID_COMPOSE_VIEW_CLASS_NAME)
            true
        } catch (e: Throwable) {
            config.logger.log("Compose not available: $e.")
            false
        }
    }

    private fun recycleAndLogBitmapDiscarded(
        bitmap: Bitmap,
        message: String = "Session Replay screenshot discarded due to screen changes.",
    ) {
        bitmap.recycle()
        config.logger.log(message)
    }

    // PixelCopy is only API >= 24 but this is already protected by the isSupported method
    @SuppressLint("NewApi")
    private fun View.toScreenshotWireframe(window: Window): RRWireframe? {
        val view = this
        if (!view.isVisible()) {
            return null
        }

        val viewId = System.identityHashCode(view)

        val coordinates = IntArray(2)
        view.getLocationOnScreen(coordinates)
        val x = coordinates[0].densityValue(displayMetrics.density)
        val y = coordinates[1].densityValue(displayMetrics.density)
        val width = view.width.densityValue(displayMetrics.density)
        val height = view.height.densityValue(displayMetrics.density)
        var base64: String? = null

        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val latch = CountDownLatch(1)
        var success = true
        val thread = HandlerThread("PostHogReplayScreenshot")
        thread.start()

        // unfortunately we cannot use the Looper.myLooper() because it will be null
        val handler = Handler(thread.looper)

        try {
            // reset the isOnDrawnCalled since we are about to take a screenshot
            isOnDrawnCalled = false

            PixelCopy.request(window, bitmap, { copyResult ->
                if (copyResult != PixelCopy.SUCCESS) {
                    recycleAndLogBitmapDiscarded(bitmap, "Session Replay PixelCopy failed: $copyResult.")
                    success = false
                } else {
                    if (!isOnDrawnCalled) {
                        val maskableWidgets = mutableListOf<Rect>()
                        val result = findMaskableWidgets(view, maskableWidgets)

                        if (result) {
                            val canvas = Canvas(bitmap)

                            maskableWidgets.forEach {
                                if (isOnDrawnCalled) {
                                    recycleAndLogBitmapDiscarded(bitmap)
                                    success = false
                                    return@forEach
                                }
                                canvas.drawRoundRect(RectF(it), 10f, 10f, paint)
                            }
                        } else {
                            recycleAndLogBitmapDiscarded(bitmap)
                            success = false
                        }
                    } else {
                        recycleAndLogBitmapDiscarded(bitmap)
                        // if isOnDrawnCalled is true, it means that the view has already been drawn
                        // again, so we don't need to draw the maskable widgets otherwise
                        // they might be out of sync (leaking possible PII)
                        success = false
                    }
                }
                // reset the isOnDrawnCalled since we've taken the screenshot
                isOnDrawnCalled = false
                latch.countDown()
            }, handler)
        } catch (e: Throwable) {
            recycleAndLogBitmapDiscarded(bitmap, "Session Replay PixelCopy failed: $e.")
            thread.quit()
            success = false
            latch.countDown()
        }

        try {
            // await for 1s max
            latch.await(1000, TimeUnit.MILLISECONDS)

            if (success) {
                base64 = bitmap.webpBase64()
            }
        } catch (e: Throwable) {
            recycleAndLogBitmapDiscarded(bitmap, "Session Replay PixelCopy timed out: $e.")
        } finally {
            isOnDrawnCalled = false
            thread.quit()
            bitmap.recycle()
        }

        return RRWireframe(
            id = viewId,
            x = x,
            y = y,
            width = width,
            height = height,
            type = "screenshot",
            base64 = base64,
            style = RRStyle(),
        )
    }

    private fun ImageView.shouldMaskImage(): Boolean {
        return isNoCapture(config.sessionReplayConfig.maskAllImages) && drawable?.shouldMaskDrawable() == true
    }

    private fun Spinner.shouldMaskSpinner(): Boolean {
        return this.isTextInputSensitive()
    }

    private fun View.toWireframe(parentId: Int? = null): RRWireframe? {
        val view = this
        if (!view.isVisible()) {
            return null
        }

        val viewId = System.identityHashCode(view)

        val coordinates = IntArray(2)
        view.getLocationOnScreen(coordinates)
        val x = coordinates[0].densityValue(displayMetrics.density)
        val y = coordinates[1].densityValue(displayMetrics.density)
        val width = view.width.densityValue(displayMetrics.density)
        val height = view.height.densityValue(displayMetrics.density)
        var base64: String? = null

        var type: String? = null
        if (view.id == android.R.id.statusBarBackground) {
            type = "status_bar"
        }
        if (view.id == android.R.id.navigationBarBackground) {
            type = "navigation_bar"
        }

        val style = RRStyle()
        view.background?.let { background ->
            background.toRGBColor()?.let { color ->
                style.backgroundColor = color
            } ?: run {
                style.backgroundImage = background.base64(view.width, view.height)
            }
        }

        var checked: Boolean? = null

        var text: String? = null
        var inputType: String? = null
        var value: Any? = null
        // button inherits from textview
        if (view is TextView) {
            val viewText = view.text?.toString()
            if (!viewText.isNullOrEmpty()) {
                text =
                    if (!view.shouldMaskTextView()) {
                        viewText
                    } else {
                        viewText.mask()
                    }
            }

            val hint = view.hint?.toString()
            if (text.isNullOrEmpty() && !hint.isNullOrEmpty()) {
                text =
                    if (!view.shouldMaskTextView()) {
                        hint
                    } else {
                        hint.mask()
                    }
            }

            type = "text"
            style.color = view.currentTextColor.toRGBColor()

            // CompoundButton is a subclass of CheckBox, RadioButton, Switch, etc
            if (view is Button && view !is CompoundButton) {
                style.borderWidth = 1
                style.borderColor = "#000000"
                type = "input"
                inputType = "button"
                value = text
                text = null
            }
//            TODO: do this when we upgrade API to 34
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
//                style.fontFamily = view.typeface?.systemFontFamilyName
//            } else {
            view.typeface?.let {
                when (it) {
                    Typeface.DEFAULT -> style.fontFamily = "sans-serif"
                    Typeface.DEFAULT_BOLD -> style.fontFamily = "sans-serif-bold"
                    Typeface.MONOSPACE -> style.fontFamily = "monospace"
                    Typeface.SERIF -> style.fontFamily = "serif"
                }
            }
//            }
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
                    val horizontalAlignment =
                        when (view.gravity.and(Gravity.HORIZONTAL_GRAVITY_MASK)) {
                            Gravity.START, Gravity.LEFT -> "left"
                            Gravity.END, Gravity.RIGHT -> "right"
                            Gravity.CENTER, Gravity.CENTER_HORIZONTAL -> "center"
                            else -> "left"
                        }
                    style.horizontalAlign = horizontalAlignment

                    val verticalAlignment =
                        when (view.gravity.and(Gravity.VERTICAL_GRAVITY_MASK)) {
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

            // left, top, right, bottom
            view.compoundDrawables.forEachIndexed { index, drawable ->
                drawable?.let {
                    val drawableBase64 = it.base64(view.width, view.height)
                    // TODO: the 2 other possible drawables (top and bottom are not common)
                    when (index) {
                        0 -> style.iconLeft = drawableBase64
//                        1 -> style.iconTop = drawableBase64
                        2 -> style.iconRight = drawableBase64
//                        3 -> style.iconBottom = drawableBase64
                    }
                }
            }

            // Do not set padding if the text is centered, otherwise the padding will be off
            if (style.verticalAlign != "center") {
                style.paddingTop = view.totalPaddingTop.densityValue(displayMetrics.density)
                style.paddingBottom = view.totalPaddingBottom.densityValue(displayMetrics.density)
            }
            if (style.horizontalAlign != "center") {
                style.paddingLeft = view.totalPaddingLeft.densityValue(displayMetrics.density)
                style.paddingRight = view.totalPaddingRight.densityValue(displayMetrics.density)
            }
        }

        var label: String? = null
        if (view is CheckBox) {
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
            val mask = view.shouldMaskSpinner()
            view.selectedItem?.let {
                val theValue =
                    if (!mask) {
                        it.toString()
                    } else {
                        it.toString().mask()
                    }
                value = theValue
            }

            view.adapter?.let {
                val items = mutableListOf<String>()
                for (i in 0 until it.count) {
                    val item = it.getItem(i)?.toString() ?: continue

                    val theItem =
                        if (!mask) {
                            item
                        } else {
                            item.mask()
                        }

                    items.add(theItem)
                }
                options = items.ifEmpty { null }
            }
        }

        if (view is ImageView) {
            type = "image"
            if (!view.shouldMaskImage()) {
                // TODO: we can probably do a LRU caching here for already captured images
                view.drawable?.let { drawable ->
                    base64 = drawable.base64(view.width, view.height)
//                    style.paddingTop = view.paddingTop.densityValue(displayMetrics.density)
//                    style.paddingBottom = view.paddingBottom.densityValue(displayMetrics.density)
//                    style.paddingLeft = view.paddingLeft.densityValue(displayMetrics.density)
//                    style.paddingRight = view.paddingRight.densityValue(displayMetrics.density)
                }
            }
        }

        var max: Int? = null // can be a Int or Float
        if (view is ProgressBar) {
            inputType = "progress"
            type = "input"
            val bar =
                if (view.isIndeterminate) {
                    "circular"
                } else {
                    max = view.max
                    value = view.progress
                    "horizontal"
                }
            style.bar = bar
        }
        if (view is RatingBar) {
            style.bar = "rating"

            // since stars allow half stars, we need to divide the max by 2, because
            // 5 stars is 10
            max = (view.max / 2)
            value = view.rating
        }

        if (view is Switch) {
            type = "input"
            inputType = "toggle"
            checked = view.isChecked
            label = text
            text = null
        }

        // TODO: people might be used androidx.webkit:webkit though
        if (view is WebView) {
            type = "web_view"
        }

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
            max = max,
        )
    }

    private fun runDrawableConverter(drawable: Drawable): Bitmap? {
        return config.sessionReplayConfig.drawableConverter?.convert(drawable)
    }

    private fun Drawable.toRGBColor(): String? {
        when (this) {
            is ColorDrawable -> {
                return color.toRGBColor()
            }

            is RippleDrawable -> {
                try {
                    return getFirstDrawable()?.toRGBColor()
                } catch (e: Throwable) {
                    // ignore
                }
            }

            is InsetDrawable -> {
                return drawable?.toRGBColor()
            }

            is GradientDrawable -> {
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
                    if (it.defaultColor != -1) {
                        return it.defaultColor.toRGBColor()
                    }
                }
            }
        }
        return null
    }

    private fun Drawable.base64(
        width: Int,
        height: Int,
        cloned: Boolean = false,
    ): String? {
        val convertedBitmap = runDrawableConverter(this)
        if (convertedBitmap != null) {
            return convertedBitmap.webpBase64()
        }

        var clonedDrawable = this
        if (!cloned) {
            clonedDrawable = copy() ?: return null
        }

        when (clonedDrawable) {
            is BitmapDrawable -> {
                try {
                    return clonedDrawable.bitmap.webpBase64()
                } catch (_: Throwable) {
                    // ignore
                }
            }

            is LayerDrawable -> {
                clonedDrawable.getFirstDrawable()?.let {
                    return it.base64(width, height)
                }
            }

            is InsetDrawable -> {
                clonedDrawable.drawable?.let {
                    return it.base64(width, height)
                }
            }
        }

        try {
            val bitmap = clonedDrawable.toBitmap(width, height)
            val base64 = bitmap.webpBase64()
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
            return base64
        } catch (_: Throwable) {
            // ignore
        }
        return null
    }

    private fun LayerDrawable.getFirstDrawable(): Drawable? {
        for (i in 0 until numberOfLayers) {
            getDrawable(i)?.let {
                return it
            }
        }

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
    ): Triple<List<RRWireframe>, List<RRWireframe>, List<RRWireframe>> {
        val oldMap = oldItems.associateBy { it.id }
        val newMap = newItems.associateBy { it.id }

        // Create HashSet to track unique IDs
        val oldItemIds = HashSet(oldItems.map { it.id })
        val newItemIds = HashSet(newItems.map { it.id })

        // Find added items by subtracting oldItemIds from newItemIds
        val addedIds = newItemIds - oldItemIds
        val addedItems = newItems.filter { it.id in addedIds }

        // Find removed items by subtracting newItemIds from oldItemIds
        val removedIds = oldItemIds - newItemIds
        val removedItems = oldItems.filter { it.id in removedIds }

        val updatedItems = mutableListOf<RRWireframe>()

        // Find updated items by finding the intersection of oldItemIds and newItemIds
        val sameItems = oldItemIds.intersect(newItemIds)

        for (id in sameItems) {
            // we have to copy without the childWireframes, otherwise they all would be different
            // if one of the child is different, but we only wanna compare the parent
            val oldItem = oldMap[id]?.copy(childWireframes = null) ?: continue
            val newItem = newMap[id] ?: continue
            val newItemCopy = newItem.copy(childWireframes = null)

            // If the items are different (any property has a different value), add the new item to the updatedItems list
            if (oldItem != newItemCopy) {
                updatedItems.add(newItem)
            }
        }

        return Triple(addedItems, removedItems, updatedItems)
    }

    private fun Drawable.toBitmap(
        width: Int,
        height: Int,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(displayMetrics, width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.MULTIPLY)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        return bitmap
    }

    private fun MotionEvent.getRawXCompat(index: Int): Float {
        return if (index < 0 || index >= pointerCount) {
            rawX // Fallback to single-touch `rawX` to prevent crashes
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                getRawX(index)
            } else {
                rawX
            }
        }
    }

    private fun MotionEvent.getRawYCompat(index: Int): Float {
        return if (index < 0 || index >= pointerCount) {
            rawY // Fallback to single-touch `rawY` to prevent crashes
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                getRawY(index)
            } else {
                rawY
            }
        }
    }

    private fun View.isNoCapture(maskInput: Boolean = false): Boolean {
        return maskInput || (tag as? String)?.lowercase()?.contains(PH_NO_CAPTURE_LABEL) == true ||
            contentDescription?.toString()?.lowercase()?.contains(PH_NO_CAPTURE_LABEL) == true
    }

    private fun Drawable.copy(): Drawable? {
        return constantState?.newDrawable()
    }

    private fun String.mask(): String {
        return "*".repeat(length)
    }

    @SuppressLint("AnnotateVersionCheck")
    private fun isSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    }

    override fun start(resumeCurrent: Boolean) {
        if (!resumeCurrent) {
            clearSnapshotStates()
        }

        isSessionReplayActive = true
    }

    private fun clearSnapshotStates() {
        // clear state so it starts with a full snapshot again
        decorViews.entries.forEach {
            resetViewSnapshotStates(it.value)
        }
    }

    override fun stop() {
        isSessionReplayActive = false
        isOnDrawnCalled = false
    }

    override fun isActive(): Boolean {
        return isSessionReplayActive
    }

    internal companion object {
        const val PH_NO_CAPTURE_LABEL: String = "ph-no-capture"
        const val ANDROID_COMPOSE_VIEW_CLASS_NAME: String = "androidx.compose.ui.platform.AndroidComposeView"
        const val ANDROID_COMPOSE_VIEW: String = "AndroidComposeView"

        @Volatile
        private var integrationInstalled = false
    }
}

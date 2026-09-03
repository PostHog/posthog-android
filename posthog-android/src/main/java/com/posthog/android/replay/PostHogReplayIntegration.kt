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
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
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
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.posthog.PostHogIntegration
import com.posthog.PostHogInterface
import com.posthog.android.PostHogAndroidConfig
import com.posthog.android.internal.MainHandler
import com.posthog.android.internal.densityValue
import com.posthog.android.internal.displayMetrics
import com.posthog.android.internal.isValid
import com.posthog.android.internal.screenSize
import com.posthog.android.internal.webpBase64
import com.posthog.android.replay.PostHogMaskModifier.PostHogReplayMask
import com.posthog.android.replay.PostHogMaskModifier.PostHogReplayUnmask
import com.posthog.android.replay.internal.BaselineResult
import com.posthog.android.replay.internal.IntHashSet
import com.posthog.android.replay.internal.MaskCaptureToken
import com.posthog.android.replay.internal.NextDrawListener.Companion.onNextDraw
import com.posthog.android.replay.internal.ViewTreeSnapshotStatus
import com.posthog.android.replay.internal.WindowDrawState
import com.posthog.android.replay.internal.isAlive
import com.posthog.android.replay.internal.isAliveAndAttachedToWindow
import com.posthog.internal.PostHogSessionManager
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
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

public class PostHogReplayIntegration(
    private val context: Context,
    private val config: PostHogAndroidConfig,
    private val mainHandler: MainHandler,
) : PostHogIntegration, PostHogSessionReplayHandler {
    // internal (not private) so tests can assert the resume path resets per-view snapshot state.
    // Main-thread writes race the capture executor's reads, and even WeakHashMap.get()
    // structurally modifies the map (stale-entry expunge), so accesses must be synchronized
    // and iteration must hold the map's monitor.
    internal val decorViews: MutableMap<View, ViewTreeSnapshotStatus> =
        Collections.synchronizedMap(WeakHashMap<View, ViewTreeSnapshotStatus>())

    private val passwordInputTypes =
        setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        )

    internal constructor(
        context: Context,
        config: PostHogAndroidConfig,
        mainHandler: MainHandler,
        replayExecutor: ExecutorService,
    ) : this(context, config, mainHandler) {
        injectedExecutor = replayExecutor
    }

    private var injectedExecutor: ExecutorService? = null

    private val executor: ExecutorService by lazy {
        injectedExecutor ?: Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("PostHogReplayThread"))
    }

    // Reuse a single HandlerThread for PixelCopy callbacks instead of
    // creating and destroying one per screenshot.
    // TODO: On API 34+, use PixelCopy.Request.Builder.ofWindow(window) which accepts an Executor
    //  directly, eliminating the need for a HandlerThread entirely. Requires compileSdk 34+.
    private var pixelCopyThread: HandlerThread? = null
    private var pixelCopyHandler: Handler? = null

    private fun ensurePixelCopyHandler(): Handler {
        pixelCopyThread?.let { thread ->
            if (thread.isAlive) {
                pixelCopyHandler?.let { return it }
            }
        }
        val thread = HandlerThread("PostHogReplayScreenshot").apply { start() }
        val handler = Handler(thread.looper)
        pixelCopyThread = thread
        pixelCopyHandler = handler
        return handler
    }

    private val displayMetrics by lazy {
        context.displayMetrics()
    }

    // Cache density to avoid repeated property access through displayMetrics
    private val screenDensity by lazy {
        displayMetrics.density
    }

    private val paint =
        Paint().apply {
            color = Color.BLACK
        }

    @Volatile
    private var isSessionReplayActive: Boolean = false

    // Set by any start that happens while config.sessionReplay is false — the manual API or an
    // event trigger. Survives stopRecording() so an internal stop (session cleared, sampled out,
    // flag off) can still resume later; only an explicit stop() or uninstall() clears it.
    @Volatile
    private var startedWithAutomaticDisabled: Boolean = false

    // Event triggers for session recording
    private val eventTriggersLock = Any()

    @Volatile
    private var triggerActivatedSessionId: String? = null

    // flutter captures snapshots, so we don't need to capture them here
    private val isNativeSdk: Boolean
        get() = (config.sdkName != "posthog-flutter")

    private var postHog: PostHogInterface? = null
    private var replayQueue: PostHogReplayQueue? = null
    private var ownsInstallation = false

    @Volatile
    private var replaySessionId: String? = null

    // Minimum duration buffering state
    private val bufferingLock = Any()

    @Volatile
    private var hasPassedMinimumDuration: Boolean = false
    private var cachedMinimumDurationMs: Long? = null

    // True while recording optimistically off the disk-cached session-replay flag and the first
    // live remote config is still pending. Snapshots are buffered (not persisted) until the server
    // confirms or rejects the flag. Always read/written under [bufferingLock].
    private var awaitingFirstRemoteConfig: Boolean = false

    // Bumped every time the gate is (re-)armed for a new session/identity. [resolveFirstRemoteConfig]
    // captures it and re-checks before migrating/clearing the buffer, so a concurrent session
    // rotation ([resetBufferingState]) that re-arms and clears the buffer can't have the in-flight
    // resolution act on the wrong window. Always read/written under [bufferingLock].
    private var bufferingGeneration: Int = 0

    private val replayBufferDelegate =
        object : PostHogReplayBufferDelegate {
            override val isBuffering: Boolean
                get() = this@PostHogReplayIntegration.isBuffering

            override val isActive: Boolean
                get() = this@PostHogReplayIntegration.isSessionReplayActive

            override fun onReplayBufferSnapshot(replayQueue: PostHogReplayQueue) {
                this@PostHogReplayIntegration.onReplayBufferSnapshot(replayQueue)
            }
        }

    internal fun onDrawCallback(drawState: WindowDrawState) {
        drawState.recordDraw()
    }

    // Reused across frames; draw-time walks only ever run on the main thread.
    private val drawSampleWalk = MaskWalk()

    internal fun onDrawCallback(
        view: View,
        drawState: WindowDrawState,
    ) {
        // Keep this first so a draw that overlaps the verified pre-walk cannot go unnoticed.
        drawState.recordDraw()

        val classifyLegacyDraw =
            !shouldVerifyMaskAlignment(view, drawState) ||
                drawState.isLegacyCaptureActive
        if (classifyLegacyDraw) {
            val screenshotCapable = config.sessionReplayConfig.screenshot || !isNativeSdk
            val isOnlyAnimationRedraw =
                screenshotCapable &&
                    !drawState.didLayoutSinceReset &&
                    (view.hasTransientState() || view.hasActiveSurfaceRendering()) &&
                    !view.isAnimationRunning()
            drawState.recordLegacyAnimationRedraw(isOnlyAnimationRedraw)
        }

        val session = drawState.beginDrawSample() ?: return
        val walk = drawSampleWalk
        walk.resetForCompareAgainst(session.compareBaseline)
        var misaligned: Boolean
        try {
            findMaskableWidgets(view, walk)
            misaligned = walk.poisoned || walk.isMisaligned()
        } catch (e: Throwable) {
            config.logger.log("Session Replay draw-time mask walk failed: $e.")
            misaligned = true
        }
        drawState.recordMaskWalk(session.token, misaligned)
    }

    /**
     * Looks for a visible surface-backed view whose pixels can change without moving its masks.
     * This keeps the legacy screenshot redraw guard compatible when direct mask verification is off.
     */
    private fun View.hasActiveSurfaceRendering(): Boolean {
        return try {
            when {
                visibility != View.VISIBLE ||
                    alpha <= 0f ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && transitionAlpha <= 0f) -> false
                this is TextureView -> isAvailable && width > 0 && height > 0
                this is SurfaceView -> width > 0 && height > 0
                this is ViewGroup -> {
                    for (i in 0 until childCount) {
                        if (getChildAt(i).hasActiveSurfaceRendering()) {
                            return true
                        }
                    }
                    false
                }
                else -> false
            }
        } catch (e: Throwable) {
            config.logger.log("Session Replay surface rendering check failed: $e.")
            false
        }
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
                                // Captured by the listeners directly so no draw can be missed
                                // before the decorViews map insertion.
                                val drawState = WindowDrawState()
                                val listener =
                                    decorView.onNextDraw(
                                        mainHandler,
                                        config.dateProvider,
                                        config.sessionReplayConfig.throttleDelayMs,
                                        { onDrawCallback(decorView, drawState) },
                                    ) {
                                        if (!isActive() || !isNativeSdk) {
                                            return@onNextDraw
                                        }

                                        executor.submit {
                                            try {
                                                generateSnapshot(WeakReference(decorView), WeakReference(window))
                                            } catch (e: Throwable) {
                                                config.logger.log("Session Replay generateSnapshot failed: $e.")
                                            }
                                        }
                                    }

                                val layoutListener =
                                    ViewTreeObserver.OnGlobalLayoutListener { drawState.recordLayout() }
                                decorView.viewTreeObserver?.addOnGlobalLayoutListener(layoutListener)

                                val status = ViewTreeSnapshotStatus(listener, layoutListener, drawState = drawState)
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
            payload["height"] = imeHeight.densityValue(screenDensity)
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

    internal val onTouchEventListener =
        TouchEventInterceptor { motionEvent, dispatch ->
            try {
                val state = dispatch(motionEvent)
                try {
                    if (!isActive()) {
                        return@TouchEventInterceptor state
                    }
                    val timestamp = config.dateProvider.currentTimeMillis()
                    // 1. prevent MotionEvent Object Is Recycled or Invalid
                    // 2. pointerCount Changed Between Checks and Access (since we call on a background thread)
                    val safeMotionEvent = MotionEvent.obtain(motionEvent)

                    executor.submit {
                        try {
                            if (!isActive()) {
                                return@submit
                            }
                            when (safeMotionEvent.action.and(MotionEvent.ACTION_MASK)) {
                                MotionEvent.ACTION_DOWN -> {
                                    generateMouseInteractions(timestamp, safeMotionEvent, RRMouseInteraction.TouchStart)
                                }
                                MotionEvent.ACTION_UP -> {
                                    generateMouseInteractions(timestamp, safeMotionEvent, RRMouseInteraction.TouchEnd)
                                }
                            }
                        } catch (e: Throwable) {
                            config.logger.log("Executor#OnTouchEventListener $safeMotionEvent failed: $e.")
                        } finally {
                            safeMotionEvent.recycle()
                        }
                    }
                } catch (e: Throwable) {
                    // does nothing
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
                val absX = motionEvent.getRawXCompat(index).toInt().densityValue(screenDensity)
                val absY = motionEvent.getRawYCompat(index).toInt().densityValue(screenDensity)

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
        status.drawState.resetSnapshotState()
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
                        status.layoutListener?.let { view.viewTreeObserver?.removeOnGlobalLayoutListener(it) }
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

    @Synchronized
    override fun install(postHog: PostHogInterface) {
        if (!isSupported() || !integrationInstalled.compareAndSet(false, true)) {
            return
        }
        ownsInstallation = true
        this.postHog = postHog

        // Wire up as buffer delegate for the replay queue
        replayQueue = config.replayQueueHolder
        replayQueue?.clearBuffer()
        replayQueue?.bufferDelegate = replayBufferDelegate

        // Load cached minimum duration from remote config (if available)
        updateCachedMinimumDuration()

        // Buffer snapshots until the first live remote config resolves, unless it already has.
        synchronized(bufferingLock) {
            awaitingFirstRemoteConfig = shouldAwaitFirstRemoteConfig()
            bufferingGeneration++
        }

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

    @Synchronized
    override fun uninstall() {
        if (!ownsInstallation) {
            return
        }
        try {
            this.postHog = null

            // Clear buffer delegate
            replayQueue?.bufferDelegate = null
            replayQueue = null
            replaySessionId = null

            Curtains.onRootViewsChangedListeners -= onRootViewsChangedListener

            // Snapshot first: clearViewListeners removes entries, which would structurally
            // modify the map mid-iteration.
            val decorViewsSnapshot = synchronized(decorViews) { decorViews.entries.map { it.toPair() } }
            decorViewsSnapshot.forEach { (view, status) ->
                clearViewListeners(view, status)
                status.drawState.invalidateMaskCapture()
            }

            startedWithAutomaticDisabled = false
            isSessionReplayActive = false

            pixelCopyThread?.quitSafely()
            pixelCopyThread = null
            pixelCopyHandler = null

            // clear to help GC
            clearSnapshotStates()
            decorViews.clear()
        } catch (e: Throwable) {
            config.logger.log("Session Replay uninstall failed: $e.")
        } finally {
            ownsInstallation = false
            integrationInstalled.set(false)
        }
    }

    /**
     * One-shot capture of the top-most native activity window, for first-party
     * PostHog wrapper SDKs (e.g. posthog-flutter) driving out-of-engine capture
     * on their own cadence. Not for app use: it shares snapshot state with the
     * normal timer-driven capture. Must be called on the main thread.
     *
     * [excludeView] (the caller's own decor view) is never captured.
     * [forceFullSnapshot] resets the decor view's snapshot state so an episode's
     * first capture is a full snapshot, not incremental mutations against a
     * player mirror that interleaved frames have invalidated. [isStillValid] is
     * re-checked on the capture thread, so work queued on an episode's last tick
     * self-drops instead of emitting a late frame.
     *
     * The return value only means the capture was scheduled; [onResult] fires on
     * the capture thread with whether a frame was actually delivered — callers
     * must treat that, not the return value, as the retry signal.
     */
    @PostHogInternalReplayApi
    public fun captureSessionReplaySnapshot(
        excludeView: View?,
        forceFullSnapshot: Boolean,
        isStillValid: () -> Boolean,
        onResult: (delivered: Boolean) -> Unit,
    ): Boolean {
        if (!isActive()) {
            return false
        }
        try {
            // Topmost ACTIVITY window only: a Dialog's decor on top would
            // collapse the replay viewport to the dialog, and a PopupWindow
            // has no phoneWindow at all — both are treated as overlays of the
            // activity window beneath them. Identified by window type
            // (callbacks are wrapped by Curtains, so `callback is Activity`
            // does not hold).
            val decorView =
                Curtains.rootViews.lastOrNull {
                    it.isAliveAndAttachedToWindow() &&
                        it.phoneWindow != null &&
                        (it.layoutParams as? WindowManager.LayoutParams)?.type ==
                        WindowManager.LayoutParams.TYPE_BASE_APPLICATION
                } ?: return false
            if (decorView === excludeView) {
                return false
            }
            val window = decorView.phoneWindow ?: return false
            if (decorViews[decorView] == null) {
                // Not tracked yet (onDecorViewReady pending): generateSnapshot
                // would bail silently — report failure so the caller retries
                // and the first-of-episode reset is not consumed.
                return false
            }
            executor.submit {
                // A throwing onResult would land in the catch below and fire a
                // second time — report exactly once per scheduled capture.
                var resultReported = false

                fun report(delivered: Boolean) {
                    if (resultReported) return
                    resultReported = true
                    onResult(delivered)
                }
                try {
                    // Validity and the first-of-episode reset both happen on
                    // the capture thread: the reset mutates snapshot status
                    // fields that are otherwise only touched here, and a
                    // stale queued capture must not emit after the episode.
                    if (!isStillValid()) {
                        // The contract promises onResult for every scheduled
                        // capture; a silent self-drop would leave the caller's
                        // in-flight tracking latched forever.
                        report(false)
                        return@submit
                    }
                    if (forceFullSnapshot) {
                        decorViews[decorView]?.let { status ->
                            status.sentFullSnapshot = false
                            status.sentMetaEvent = false
                            status.lastSnapshot = null
                        }
                    }
                    val delivered = generateSnapshot(WeakReference(decorView), WeakReference(window), forceScreenshot = true)
                    if (!delivered) {
                        config.logger.log("Session Replay bridge capture produced no frame (will retry next tick).")
                    }
                    report(delivered)
                } catch (e: Throwable) {
                    config.logger.log("Session Replay bridge capture failed: $e.")
                    report(false)
                }
            }
            return true
        } catch (e: Throwable) {
            config.logger.log("Session Replay bridge capture failed: $e.")
            return false
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

    // internal (not private) so tests can drive a snapshot pass directly.
    // Returns whether a frame was actually produced (false on every early bail),
    // so the bridge caller can tell "captured" from "silently skipped".
    internal fun generateSnapshot(
        viewRef: WeakReference<View>,
        windowRef: WeakReference<Window>,
        forceScreenshot: Boolean = false,
    ): Boolean {
        // Early bail if stopped and this is processing previous generateSnapshot() from executor.submit
        if (!isActive()) return false

        val view = viewRef.get() ?: return false
        val status = decorViews[view] ?: return false
        val window = windowRef.get() ?: return false

        // Check view is still alive to avoid native crashes
        if (!view.isAlive()) return false

        val timestamp = config.dateProvider.currentTimeMillis()

        val useScreenshot = config.sessionReplayConfig.screenshot || forceScreenshot
        val wireframe =
            if (useScreenshot) {
                view.toScreenshotWireframe(
                    window,
                    status.drawState,
                ) ?: return false
            } else {
                warnIfComposeWireframe(view, status.drawState)
                view.toWireframe() ?: return false
            }

        // if the decorView has no backgroundColor, we use the theme color
        // no need to do this if we are capturing a screenshot
        if (wireframe.style?.backgroundColor == null && !useScreenshot) {
            context.theme?.toRGBColor()?.let {
                wireframe.style?.backgroundColor = it
            }
        }

        val events = mutableListOf<RREvent>()

        if (!status.sentMetaEvent) {
            val title = view.phoneWindow?.attributes?.title?.toString()?.substringAfter("/") ?: ""
            // TODO: cache and compare, if size changes, we send a ViewportResize event

            val screenSizeInfo = view.context.screenSize() ?: return false

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
        return true
    }

    /**
     * Adapted from https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/core/java/android/view/View.java;l=11620;bpv=0;bpt=1
     */
    private fun View.isVisible(walk: MaskWalk? = null): Boolean {
        try {
            if (width <= 0 || height <= 0) return false

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

                // Check if view is in a stable state before accessing matrix-dependent operations
                return hasGlobalVisibleRect(walk)

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

    internal fun Drawable.shouldMaskDrawable(): Boolean {
        return when (this) {
            is InsetDrawable, is ColorDrawable, is VectorDrawable, is GradientDrawable, is LayerDrawable -> false
            // otherwise its not accessible anyway
            is BitmapDrawable -> bitmap?.isValid() == true
            else -> true
        }
    }

    /**
     * Checks whether the view has a non-empty visible rect. Walk callers pass their walk so
     * its scratch objects are reused; other callers allocate per call (see the wireframe
     * vs delayed-screenshot-walk race test).
     */
    private fun View.hasGlobalVisibleRect(walk: MaskWalk? = null): Boolean {
        return if (isViewStateStableForMatrixOperations()) {
            if (walk != null) {
                getGlobalVisibleRect(walk.scratchRect, walk.scratchPoint)
            } else {
                getGlobalVisibleRect(Rect(), Point())
            }
        } else {
            false
        }
    }

    private fun View.isViewStateStableForMatrixOperations(): Boolean {
        return try {
            // isAttachedToWindow implies an attached root: attach info propagates down from
            // the ViewRootImpl, so a separate rootView check would be redundant.
            isAttachedToWindow &&
                (isLaidOut || PostHogSessionManager.isReactNative) &&
                // Check if view has valid dimensions
                width > 0 && height > 0 &&
                // Check if view is not in layout transition (API 18+)
                !isInLayout &&
                // Check if view doesn't have transient state (animations, etc.)
                !hasTransientState() &&
                // Check if view is not currently being animated
                !isAnimationRunning() &&
                // Check if view tree is not currently computing layout
                !isComputingLayout()
        } catch (e: Throwable) {
            // If any check fails, assume unstable state
            config.logger.log("Session Replay view state check failed: $e.")
            false
        }
    }

    private fun View.isAnimationRunning(): Boolean {
        return animation?.hasStarted() == true && animation?.hasEnded() != true
    }

    private fun View.isComputingLayout(): Boolean {
        // Check if direct parent ViewGroup is in layout
        return (parent as? ViewGroup)?.isInLayout == true
    }

    private fun View.isTextInputSensitive(ancestorUnmasked: Boolean = false): Boolean {
        if (ancestorUnmasked || isUnmasked()) return false
        return isNoCapture(config.sessionReplayConfig.maskAllTextInputs)
    }

    private fun View.isAnyInputSensitive(ancestorUnmasked: Boolean = false): Boolean {
        if (ancestorUnmasked || isUnmasked()) return false
        return isNoCapture(config.sessionReplayConfig.maskAllTextInputs) || config.sessionReplayConfig.maskAllImages
    }

    private fun TextView.shouldMaskTextView(ancestorUnmasked: Boolean = false): Boolean {
        // inputType is 0-based
        return this.isTextInputSensitive(ancestorUnmasked) || passwordInputTypes.contains(inputType - 1)
    }

    // poisoned = the rect list may be incomplete (a rendered view had unknowable geometry, or
    // the Compose pass timed out), so this walk cannot prove mask alignment.
    internal class MaskWalk(
        val failClosed: Boolean = true,
        private val shouldAbort: (() -> Boolean)? = null,
    ) {
        val rects: MutableList<Rect> = mutableListOf()
        var poisoned: Boolean = false
        var aborted: Boolean = false
            private set

        // Compare mode: rects stream against the capture baseline instead of being stored, so
        // the per-frame draw-time walk allocates nothing and can stop on the first mismatch.
        private var baseline: List<Rect>? = null
        private var baselineCursor = 0
        private var misaligned = false

        // Scratch objects; a walk is confined to a single thread.
        val scratchRect = Rect()
        val scratchPoint = Point()
        val visitedViews = IntHashSet()

        fun resetForCompareAgainst(baselineRects: List<Rect>) {
            rects.clear()
            poisoned = false
            aborted = false
            baseline = baselineRects
            baselineCursor = 0
            misaligned = false
            visitedViews.clear()
        }

        fun addRect(rect: Rect) {
            val baseline = baseline
            if (baseline == null) {
                // Deep copy: rect is usually this walk's shared scratch.
                rects.add(Rect(rect))
            } else if (baselineCursor >= baseline.size || baseline[baselineCursor++] != rect) {
                misaligned = true
            }
        }

        // True when the streamed rects did not exactly match the baseline (order-sensitive,
        // same as List.equals on the stored rects would be).
        fun isMisaligned(): Boolean {
            val baseline = baseline ?: return misaligned
            return misaligned || baselineCursor != baseline.size
        }

        // Stopping early is monotone-safe: neither poisoned nor misaligned can be unset, and
        // both verdicts already seal the walk's outcome as "discard".
        val shouldStop: Boolean
            get() {
                if (shouldAbort?.invoke() == true) {
                    aborted = true
                }
                return poisoned || misaligned || aborted
            }
    }

    // internal (not private) so tests and benchmarks can drive walks directly.
    internal fun findMaskableWidgets(
        view: View,
        walk: MaskWalk,
    ) {
        if (walk.shouldStop) {
            return
        }

        // Guards against a pathological hierarchy (broken custom getChildAt) recursing forever.
        if (!walk.visitedViews.add(System.identityHashCode(view))) {
            return
        }

        var walkChildren = false

        when {
            view.isComposeView() -> {
                findMaskableComposeWidgets(view, walk)
                if (walk.shouldStop) {
                    return
                }
                // Also walk View children for interop scenarios (AndroidView, FragmentContainerView, etc.)
                walkChildren = true
            }

            view.isUnmasked() -> {
                // ph-no-mask has precedence, skip masking
            }

            view.isNoCapture() -> {
                view.addGlobalVisibleRect(walk)
            }

            view is TextView -> {
                // Only emptiness matters here; toString() would copy the text on every walk.
                val hasContent = view.text?.isNotEmpty() == true || view.hint?.isNotEmpty() == true
                if (hasContent && view.shouldMaskTextView()) {
                    view.addTextAreaGlobalVisibleRect(walk)
                }
            }

            view is Spinner -> {
                if (view.shouldMaskSpinner()) {
                    view.addGlobalVisibleRect(walk)
                }
            }

            view is ImageView -> {
                if (view.shouldMaskImage()) {
                    view.addGlobalVisibleRect(walk)
                }
            }

            view is WebView -> {
                if (view.isAnyInputSensitive()) {
                    view.addGlobalVisibleRect(walk)
                }
            }

            view is ViewGroup && view.childCount > 0 -> {
                walkChildren = true
            }
        }

        if (walkChildren && view is ViewGroup && view.childCount > 0) {
            for (i in 0 until view.childCount) {
                if (walk.shouldStop) {
                    return
                }

                val viewChild = view.getChildAt(i) ?: continue

                if (!viewChild.isVisible(walk)) {
                    // A skipped-but-rendered view could be a masked widget we cannot place.
                    if (walk.failClosed && viewChild.isRenderedButUnplaceable()) {
                        walk.poisoned = true
                        return
                    }
                    continue
                }

                findMaskableWidgets(viewChild, walk)
            }
        }
    }

    private fun View.addGlobalVisibleRect(walk: MaskWalk) {
        if (isViewStateStableForMatrixOperations()) {
            getGlobalVisibleRect(walk.scratchRect, walk.scratchPoint)
            walk.addRect(walk.scratchRect)
        }
    }

    /**
     * Adds the global visible rect of just the text content area within a TextView.
     * For EditText and Button subclasses this excludes the padding and compound drawables,
     * masking only the text area. For regular TextView, the full view rect is used.
     */
    private fun TextView.addTextAreaGlobalVisibleRect(walk: MaskWalk) {
        if (!isViewStateStableForMatrixOperations()) {
            return
        }
        getGlobalVisibleRect(walk.scratchRect, walk.scratchPoint)
        val rect = walk.scratchRect
        // Only adjust bounds for views that typically have significant padding or compound
        // drawables (EditText border/underline, Button background padding).
        if (this is EditText || this is Button) {
            val textAreaLeft = rect.left + compoundPaddingLeft
            val textAreaTop = rect.top + compoundPaddingTop
            val textAreaRight = rect.right - compoundPaddingRight
            val textAreaBottom = rect.bottom - compoundPaddingBottom
            // Fall back to the full rect if the text area is too small
            if (textAreaRight > textAreaLeft && textAreaBottom > textAreaTop) {
                rect.set(textAreaLeft, textAreaTop, textAreaRight, textAreaBottom)
            }
        }
        walk.addRect(rect)
    }

    // Rendered per its flags, but its geometry is momentarily unknowable (mid animation,
    // transient state, mid-layout), so no trustworthy mask rect can be produced for it.
    private fun View.isRenderedButUnplaceable(): Boolean {
        return visibility == View.VISIBLE &&
            width > 0 && height > 0 &&
            !isViewStateStableForMatrixOperations()
    }

    // Inline when already on the main thread (posting there would deadlock); otherwise
    // post-and-wait. Returns null on timeout or throw -- callers must treat that as failure.
    private fun <T> runOnMainThreadBlocking(block: () -> T): T? {
        if (Looper.myLooper() == mainHandler.handler.looper) {
            return try {
                block()
            } catch (e: Throwable) {
                config.logger.log("Session Replay main-thread hop failed: $e")
                null
            }
        }
        val latch = CountDownLatch(1)
        var result: T? = null
        mainHandler.handler.post {
            try {
                // Caught here too: an uncaught throw would otherwise escape onto the
                // main Looper and crash the host app.
                result = block()
            } catch (e: Throwable) {
                config.logger.log("Session Replay main-thread hop failed: $e")
            } finally {
                latch.countDown()
            }
        }
        return try {
            if (latch.await(1000, TimeUnit.MILLISECONDS)) result else null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            config.logger.log("Session Replay main-thread hop failed: $e")
            null
        } catch (e: Throwable) {
            config.logger.log("Session Replay main-thread hop failed: $e")
            null
        }
    }

    private fun findMaskableComposeWidgets(
        view: View,
        walk: MaskWalk,
    ) {
        // Local list so a timed-out runnable that fires late cannot mutate the walk.
        val maskableWidgets = mutableListOf<Rect>()
        var traversalSucceeded = false

        val traversal =
            Runnable {
                try {
                    val semanticsOwner =
                        (view as? RootForTest)?.semanticsOwner ?: run {
                            config.logger.log("View is not a RootForTest: $view")
                            return@Runnable
                        }
                    val semanticsNodes = semanticsOwner.getAllSemanticsNodes(true)

                    semanticsNodes.forEach { node ->
                        val hasText = node.config.contains(SemanticsProperties.Text)
                        val hasEditableText = node.config.contains(SemanticsProperties.EditableText)
                        val hasPassword = node.config.contains(SemanticsProperties.Password)
                        val hasImage = node.config.contains(SemanticsProperties.ContentDescription)

                        // isEnabled=false means the modifier has no effect, as if it was never applied
                        // Check the node itself and its ancestors for mask/unmask modifiers
                        val isMaskEnabled = node.hasActiveModifier(PostHogReplayMask)
                        val isUnmaskEnabled = node.hasActiveModifier(PostHogReplayUnmask)

                        when {
                            // postHogUnmask has precedence over everything, skip masking
                            isUnmaskEnabled -> {
                                // do not mask this node
                            }

                            // postHogMask forces masking
                            isMaskEnabled -> {
                                maskableWidgets.add(node.boundsInWindow.toRect())
                            }

                            // no active modifier, apply default config rules
                            else -> {
                                when {
                                    (hasText || hasEditableText) &&
                                        (config.sessionReplayConfig.maskAllTextInputs || hasPassword) -> {
                                        maskableWidgets.add(node.boundsInWindow.toRect())
                                    }

                                    hasImage && config.sessionReplayConfig.maskAllImages -> {
                                        maskableWidgets.add(node.boundsInWindow.toRect())
                                    }
                                }
                            }
                        }
                    }
                    traversalSucceeded = true
                } catch (e: Throwable) {
                    // Compose APIs vary by version. Missing masks must fail closed.
                    config.logger.log("Session Replay findMaskableComposeWidgets (main thread) failed: $e")
                }
            }

        // Compose requires main-thread access. Draw-time verification already runs on main, where
        // posting and waiting would deadlock, so execute inline in that case.
        val completed = runOnMainThreadBlocking { traversal.run() } != null

        if (completed && traversalSucceeded) {
            // Feed through addRect on the walk's owner thread so compare mode also covers
            // Compose rects.
            for (rect in maskableWidgets) {
                walk.addRect(rect)
            }
        } else if (walk.failClosed) {
            // Compose mask rects are missing or incomplete, so fail closed.
            walk.poisoned = true
        }
    }

    private fun androidx.compose.ui.geometry.Rect.toRect(): Rect {
        return Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
    }

    /**
     * Checks if the given semantics node or any of its ancestors has the specified
     * modifier actively enabled (value is true).
     * This allows postHogMask and postHogUnmask to propagate to all descendants.
     */
    private fun SemanticsNode.hasActiveModifier(key: SemanticsPropertyKey<Boolean>): Boolean {
        var current: SemanticsNode? = this
        while (current != null) {
            if (current.config.contains(key) && current.config[key]) {
                return true
            }
            current = current.parent
        }
        return false
    }

    private fun View.isComposeView(): Boolean {
        return isComposeAvailable && this.javaClass.name.contains(ANDROID_COMPOSE_VIEW)
    }

    // Compose recomposes on almost every frame, and the legacy redraw classifier can never treat a
    // Compose redraw as animation-only, so the legacy path discards every frame and the recording
    // stays blank. Route Compose-rooted windows onto the verified path, which compares real mask
    // geometry, so pixel-only redraws survive.
    private fun shouldVerifyMaskAlignment(
        view: View,
        drawState: WindowDrawState,
    ): Boolean {
        return config.sessionReplayConfig.verifyScreenshotMaskAlignment ||
            view.isComposeRooted(drawState)
    }

    // Fires once per process: repeating it on every snapshot would flood logcat, and one line
    // is enough to point the developer at the option that fixes the recording. Read before the
    // Compose check too, so a warned process never pays for the tree walk again.
    private val composeWireframeWarningFired = AtomicBoolean(false)

    // Wireframes are built from classic Android View types only, so a Compose-rooted window
    // produces an almost empty tree that plays back as a blank screen. Say so, because the
    // capture itself keeps succeeding and the developer gets no other signal.
    private fun warnIfComposeWireframe(
        view: View,
        drawState: WindowDrawState,
    ) {
        // The logger drops the message while debug logging is off, and PostHog.debug(true) can
        // turn it on at any point, so the once-per-process budget must not be spent on a line
        // nobody receives. Reading it up front also skips the Compose check while debug is off.
        if (composeWireframeWarningFired.get() ||
            !config.logger.isEnabled() ||
            !view.isComposeRooted(drawState)
        ) {
            return
        }
        if (composeWireframeWarningFired.compareAndSet(false, true)) {
            config.logger.log(
                "Session Replay found a Jetpack Compose window, but wireframe capture is on. " +
                    "Wireframes only cover classic Android Views, so the recording will be blank. " +
                    "Set sessionReplayConfig.screenshot = true to record Compose screens.",
            )
        }
    }

    private fun View.isComposeRooted(drawState: WindowDrawState): Boolean {
        drawState.composeRooted?.let { return it }
        if (!isComposeAvailable) {
            // Can never become true, so skip the main-thread hop entirely.
            drawState.composeRooted = false
            return false
        }

        if (!drawState.shouldRecheckComposeRoot(config.dateProvider.nanoTime())) {
            return false
        }

        // The View hierarchy is main-thread-owned, so detection must run there: inline when
        // already on it, otherwise post and wait, exactly like findMaskableComposeWidgets.
        val rooted = runOnMainThreadBlocking { containsComposeView() }

        // A swallowed failure cached as false would silently restore the every-frame-discard
        // bug, so only a definite verdict is cached; "unknown" retries on the next draw.
        if (rooted == null) {
            drawState.clearComposeRootCheck()
            return false
        }
        drawState.composeRooted = rooted
        return rooted
    }

    // Scratch for the Compose-root walk; like the mask walks, it only runs on the main thread.
    private val composeRootVisitedViews = IntHashSet()

    private fun View.containsComposeView(): Boolean? {
        return try {
            composeRootVisitedViews.clear()
            containsComposeView(composeRootVisitedViews)
        } catch (e: Throwable) {
            config.logger.log("Session Replay Compose view detection failed: $e.")
            null
        }
    }

    private fun View.containsComposeView(visitedViews: IntHashSet): Boolean {
        if (!visitedViews.add(System.identityHashCode(this))) {
            return false
        }
        if (isComposeView()) {
            return true
        }
        if (this is ViewGroup) {
            for (i in 0 until childCount) {
                if (getChildAt(i)?.containsComposeView(visitedViews) == true) {
                    return true
                }
            }
        }
        return false
    }

    // Warns once after a run of discards so a silently blank recording stops being invisible.
    private fun recordScreenshotDiscarded(drawState: WindowDrawState) {
        if (drawState.recordScreenshotDiscard()) {
            config.logger.log(
                "Session Replay discarded several screenshots in a row during capture; the " +
                    "recording may be blank. This can be caused by the screen changing during " +
                    "capture, PixelCopy failing or timing out, or bitmap encoding failing.",
            )
        }
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

    // A frame is safe to ship only when every available signal agrees that the mask geometry
    // remained aligned. Draw-time walks additionally invalidate the active capture if geometry
    // changes and returns to its starting position between these endpoint walks.
    internal fun shouldKeepFrame(
        drawState: WindowDrawState,
        preWalk: MaskWalk,
        postWalk: MaskWalk,
    ): Boolean {
        return !preWalk.poisoned &&
            !postWalk.poisoned &&
            !drawState.didLayoutSinceReset &&
            preWalk.rects == postWalk.rects
    }

    // A screenshot capture armed for draw verification: the token draws are checked
    // against, plus the pre-walk whose rects became the baseline.
    private class ArmedMaskCapture(
        val token: MaskCaptureToken,
        val preWalk: MaskWalk,
    )

    // Arms the capture before the pre-walk so no draw can slip between the walk and the
    // arming. A draw overlapping the pre-walk may leave the walked baseline torn, so that
    // attempt is thrown away and re-walked from scratch under a fresh capture, bounded so
    // a screen that redraws during every attempt discards instead of looping. Layout,
    // poison, and external invalidation discard immediately.
    private fun runArmMaskCaptureLoop(
        view: View,
        drawState: WindowDrawState,
    ): ArmedMaskCapture? {
        var armed: ArmedMaskCapture? = null
        for (attempt in 0 until MAX_BASELINE_ARM_ATTEMPTS) {
            val token = drawState.beginMaskCapture()
            val preWalk = MaskWalk()
            try {
                findMaskableWidgets(view, preWalk)
            } catch (e: Throwable) {
                config.logger.log("Session Replay mask walk failed: $e.")
                preWalk.poisoned = true
            }
            if (preWalk.poisoned) {
                drawState.cancelMaskCapture(token)
                break
            }
            when (drawState.setBaseline(token, preWalk.rects)) {
                BaselineResult.ARMED -> {
                    armed = ArmedMaskCapture(token, preWalk)
                    break
                }
                BaselineResult.TORN_BY_DRAW -> drawState.cancelMaskCapture(token)
                BaselineResult.UNKEEPABLE -> {
                    drawState.cancelMaskCapture(token)
                    break
                }
            }
        }
        return armed
    }

    private fun armMaskCapture(
        view: View,
        drawState: WindowDrawState,
    ): ArmedMaskCapture? {
        // The whole loop runs in ONE main-thread message so a draw can't land between
        // beginMaskCapture() and setBaseline() -- drawCount can't move mid-walk, which also makes
        // TORN_BY_DRAW unreachable here; the retry stays as a guard if that ever changes. Nested
        // run-on-main calls from the pre-walk (e.g. a ComposeView) run inline, already on main.
        if (Looper.myLooper() == mainHandler.handler.looper) {
            return try {
                runArmMaskCaptureLoop(view, drawState)
            } catch (e: Throwable) {
                config.logger.log("Session Replay main-thread hop failed: $e")
                null
            }
        }

        // Not the generic runOnMainThreadBlocking: on timeout the posted Runnable below still
        // runs later and must not leave an armed capture nobody will ever consume. `claimed`
        // makes the result claimable exactly once -- whichever side (the posted block finishing,
        // or this waiter giving up) gets there first wins; the loser either returns null (waiter)
        // or cancels its own token (block), so activeCapture is never left orphaned.
        val latch = CountDownLatch(1)
        val claimed = AtomicBoolean(false)
        var result: ArmedMaskCapture? = null
        mainHandler.handler.post {
            try {
                val armed = runArmMaskCaptureLoop(view, drawState)
                // Publish before the CAS, not after: on timeout the waiter reads `result` only
                // once its own CAS fails, and it is the CAS's volatile write that makes this
                // assignment visible. Writing after would let the waiter observe a claimed
                // capture with a still-null result and orphan it.
                result = armed
                if (!claimed.compareAndSet(false, true)) {
                    armed?.let { drawState.cancelMaskCapture(it.token) }
                }
            } catch (e: Throwable) {
                config.logger.log("Session Replay main-thread hop failed: $e")
            } finally {
                latch.countDown()
            }
        }
        return try {
            if (latch.await(1000, TimeUnit.MILLISECONDS)) {
                result
            } else if (claimed.compareAndSet(false, true)) {
                // We won the claim race; the block will see claimed == true and cancel.
                null
            } else {
                // The block already claimed and published its result before we could -- safe to
                // read after the failed CAS establishes happens-before.
                result
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            config.logger.log("Session Replay main-thread hop failed: $e")
            null
        } catch (e: Throwable) {
            config.logger.log("Session Replay main-thread hop failed: $e")
            null
        }
    }

    private fun Bitmap.paintScreenshotMasks(rects: List<Rect>): Boolean {
        if (!isValid()) {
            this@PostHogReplayIntegration.config.logger.log("Session Replay Bitmap is invalid.")
            return false
        }

        val canvas =
            try {
                Canvas(this)
            } catch (e: Throwable) {
                this@PostHogReplayIntegration.config.logger.log("Session Replay Canvas creation failed: $e.")
                return false
            }

        val maskRect = RectF()
        rects.forEach {
            maskRect.set(it)
            canvas.drawRoundRect(maskRect, 10f, 10f, paint)
        }
        return true
    }

    private fun View.maskVerifiedScreenshot(
        bitmap: Bitmap,
        drawState: WindowDrawState,
        armedCapture: ArmedMaskCapture,
    ): Boolean {
        val postWalk = MaskWalk()
        // A layout pass or an invalidating draw sample already sealed the verdict as discard,
        // so the post-copy walk would be wasted work.
        val alreadyDoomed = drawState.isCaptureInvalid(armedCapture.token)
        if (!alreadyDoomed) {
            findMaskableWidgets(this, postWalk)
        }

        val captureAligned =
            drawState.finishMaskCapture(
                armedCapture.token,
                postWalk.rects,
                postWalk.poisoned,
            )
        if (!captureAligned || !shouldKeepFrame(drawState, armedCapture.preWalk, postWalk)) {
            // Masks may be out of sync with the pixels, discard to avoid a PII leak.
            config.logger.log("Session Replay screenshot discarded due to screen changes.")
            return false
        }
        return bitmap.paintScreenshotMasks(postWalk.rects)
    }

    private fun View.maskLegacyScreenshot(
        bitmap: Bitmap,
        drawState: WindowDrawState,
    ): Boolean {
        val unsafeRedraw = { drawState.isOnDrawnCalled && !drawState.isOnlyAnimationRedraw }
        if (unsafeRedraw()) {
            config.logger.log("Session Replay screenshot discarded due to screen changes.")
            return false
        }

        val walk = MaskWalk(failClosed = false, shouldAbort = unsafeRedraw)
        findMaskableWidgets(this, walk)
        if (walk.aborted || unsafeRedraw()) {
            config.logger.log("Session Replay screenshot discarded due to screen changes.")
            return false
        }

        if (!bitmap.isValid()) {
            config.logger.log("Session Replay Bitmap is invalid.")
            return false
        }
        val canvas =
            try {
                Canvas(bitmap)
            } catch (e: Throwable) {
                config.logger.log("Session Replay Canvas creation failed: $e.")
                return false
            }
        val maskRect = RectF()
        walk.rects.forEach {
            if (unsafeRedraw()) {
                config.logger.log("Session Replay screenshot discarded due to screen changes.")
                return false
            }
            maskRect.set(it)
            canvas.drawRoundRect(maskRect, 10f, 10f, paint)
        }
        return true
    }

    // PixelCopy is only API >= 24 but this is already protected by the isSupported method
    @SuppressLint("NewApi")
    private fun View.toScreenshotWireframe(
        window: Window,
        drawState: WindowDrawState,
    ): RRWireframe? {
        val view = this
        if (!view.isVisible()) {
            return null
        }

        val viewId = System.identityHashCode(view)

        val coordinates = IntArray(2)
        if (view.isViewStateStableForMatrixOperations()) {
            view.getLocationOnScreen(coordinates)
        } else {
            // Use zero coordinates as fallback when view state is unstable
            coordinates[0] = 0
            coordinates[1] = 0
        }
        val x = coordinates[0].densityValue(screenDensity)
        val y = coordinates[1].densityValue(screenDensity)
        val width = view.width.densityValue(screenDensity)
        val height = view.height.densityValue(screenDensity)
        var base64: String? = null

        val verifyMaskAlignment = shouldVerifyMaskAlignment(view, drawState)
        val armedCapture =
            if (verifyMaskAlignment) {
                drawState.reset()
                // The pre-walk samples the baseline before the pixels freeze. Once armed, draws are
                // verified against it, so pixel-only redraws survive while geometry changes discard.
                armMaskCapture(view, drawState)
            } else {
                drawState.beginLegacyCapture()
                null
            }
        if (verifyMaskAlignment && armedCapture == null) {
            config.logger.log("Session Replay screenshot discarded due to screen changes.")
            recordScreenshotDiscarded(drawState)
            return null
        }
        val bitmap: Bitmap
        val handler: Handler
        try {
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            handler = ensurePixelCopyHandler()
        } catch (e: Throwable) {
            armedCapture?.let { drawState.cancelMaskCapture(it.token) }
            if (verifyMaskAlignment) {
                drawState.reset()
            } else {
                drawState.finishLegacyCapture()
            }
            config.logger.log("Session Replay screenshot setup failed: $e.")
            recordScreenshotDiscarded(drawState)
            return null
        }

        val latch = CountDownLatch(1)
        var success = true

        // Track whether the PixelCopy callback has finished to avoid recycling the bitmap
        // while the callback is still using it (e.g. if latch.await times out).
        // We use the latch itself as the synchronization mechanism (await happens-before countDown)
        var callbackCompleted = false

        try {
            PixelCopy.request(window, bitmap, { copyResult ->
                try {
                    if (copyResult != PixelCopy.SUCCESS) {
                        config.logger.log("Session Replay PixelCopy failed: $copyResult.")
                        success = false
                    } else {
                        success =
                            if (armedCapture != null) {
                                view.maskVerifiedScreenshot(bitmap, drawState, armedCapture)
                            } else {
                                view.maskLegacyScreenshot(bitmap, drawState)
                            }
                    }
                } catch (e: Throwable) {
                    config.logger.log("Session Replay PixelCopy failed: $e.")
                    success = false
                } finally {
                    callbackCompleted = true
                    latch.countDown()
                }
            }, handler)
        } catch (e: Throwable) {
            config.logger.log("Session Replay PixelCopy failed: $e.")
            success = false
            callbackCompleted = true
            latch.countDown()
        }

        try {
            // On timeout the masks aren't painted yet, so the bitmap must not be shipped.
            if (latch.await(1000, TimeUnit.MILLISECONDS) && success) {
                base64 = bitmap.webpBase64()
            }
        } catch (e: Throwable) {
            config.logger.log("Session Replay PixelCopy timed out: $e.")
        } finally {
            armedCapture?.let { drawState.cancelMaskCapture(it.token) }
            if (verifyMaskAlignment) {
                drawState.reset()
            } else {
                drawState.finishLegacyCapture()
            }
            // Only recycle the bitmap if the callback has completed.
            // If the latch timed out, the PixelCopy callback may still be writing to the bitmap
            // on another thread; recycling it now would cause a native SIGSEGV.
            if (callbackCompleted && !bitmap.isRecycled) {
                bitmap.recycle()
            }
        }

        // A discarded capture (PixelCopy failure, or a redraw race that
        // invalidates mask alignment) leaves base64 null. Emitting the
        // wireframe anyway ships an imageless "screenshot" that the player
        // renders as its placeholder tile — a visible flash. Skip the frame
        // instead; the caller retries on the next capture.
        if (base64 == null) {
            recordScreenshotDiscarded(drawState)
            return null
        }
        drawState.resetScreenshotDiscards()

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

    private fun ImageView.shouldMaskImage(ancestorUnmasked: Boolean = false): Boolean {
        if (ancestorUnmasked || isUnmasked()) return false
        return isNoCapture(config.sessionReplayConfig.maskAllImages) && drawable?.shouldMaskDrawable() == true
    }

    private fun Spinner.shouldMaskSpinner(ancestorUnmasked: Boolean = false): Boolean {
        return this.isTextInputSensitive(ancestorUnmasked)
    }

    private fun View.toWireframe(
        parentId: Int? = null,
        ancestorUnmasked: Boolean = false,
    ): RRWireframe? {
        val view = this
        if (!view.isVisible()) {
            return null
        }

        val isUnmasked = ancestorUnmasked || view.isUnmasked()

        val viewId = System.identityHashCode(view)

        val coordinates = IntArray(2)
        if (view.isViewStateStableForMatrixOperations()) {
            view.getLocationOnScreen(coordinates)
        } else {
            // Use zero coordinates as fallback when view state is unstable
            coordinates[0] = 0
            coordinates[1] = 0
        }
        val x = coordinates[0].densityValue(screenDensity)
        val y = coordinates[1].densityValue(screenDensity)
        val width = view.width.densityValue(screenDensity)
        val height = view.height.densityValue(screenDensity)
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
                    if (!view.shouldMaskTextView(isUnmasked)) {
                        viewText
                    } else {
                        viewText.mask()
                    }
            }

            val hint = view.hint?.toString()
            if (text.isNullOrEmpty() && !hint.isNullOrEmpty()) {
                text =
                    if (!view.shouldMaskTextView(isUnmasked)) {
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
            style.fontSize = view.textSize.toInt().densityValue(screenDensity)
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
                style.paddingTop = view.totalPaddingTop.densityValue(screenDensity)
                style.paddingBottom = view.totalPaddingBottom.densityValue(screenDensity)
            }
            if (style.horizontalAlign != "center") {
                style.paddingLeft = view.totalPaddingLeft.densityValue(screenDensity)
                style.paddingRight = view.totalPaddingRight.densityValue(screenDensity)
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
            val mask = view.shouldMaskSpinner(isUnmasked)
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
            if (!view.shouldMaskImage(isUnmasked)) {
                // TODO: we can probably do a LRU caching here for already captured images
                view.drawable?.let { drawable ->
                    base64 = drawable.base64(view.width, view.height)
//                    style.paddingTop = view.paddingTop.densityValue(screenDensity)
//                    style.paddingBottom = view.paddingBottom.densityValue(screenDensity)
//                    style.paddingLeft = view.paddingLeft.densityValue(screenDensity)
//                    style.paddingRight = view.paddingRight.densityValue(screenDensity)
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
                viewChild.toWireframe(parentId = viewId, ancestorUnmasked = isUnmasked)?.let {
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

    @SuppressLint("NewApi")
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
            bitmap.recycle()
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
        return maskInput || (tag as? String)?.contains(PH_NO_CAPTURE_LABEL, ignoreCase = true) == true ||
            contentDescription?.toString()?.contains(PH_NO_CAPTURE_LABEL, ignoreCase = true) == true
    }

    private fun View.isUnmasked(): Boolean {
        return (tag as? String)?.contains(PH_NO_MASK_LABEL, ignoreCase = true) == true ||
            contentDescription?.toString()?.contains(PH_NO_MASK_LABEL, ignoreCase = true) == true
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
        // Check if we should wait for event triggers before starting
        if (shouldWaitForEventTriggers()) {
            val triggers = config.remoteConfigHolder?.getEventTriggers()
            config.logger.log(
                "[Session Replay] Event triggers configured. Integration will not start until any of these events are captured: $triggers",
            )
            return
        }

        val currentSessionId = postHog?.getSessionId()?.toString()
        resetSessionStateIfNeeded(currentSessionId, force = !resumeCurrent)

        startedWithAutomaticDisabled = !config.sessionReplay
        isSessionReplayActive = true

        if (!resumeCurrent) {
            // Without this, on a static UI the first user-driven onDraw can be tens of seconds
            // away — and incremental events (type:3) would ship under the new session before
            // the meta + full-snapshot keyframes (type:4 + type:2) needed to render them.
            mainHandler.handler.post {
                synchronized(decorViews) {
                    decorViews.keys.forEach { it.postInvalidate() }
                }
            }
        }
    }

    private fun clearSnapshotStates() {
        // clear state so it starts with a full snapshot again
        synchronized(decorViews) {
            decorViews.entries.forEach {
                resetViewSnapshotStates(it.value)
            }
        }
    }

    override fun stop() {
        startedWithAutomaticDisabled = false
        stopRecording()
    }

    private fun stopRecording() {
        isSessionReplayActive = false
        synchronized(decorViews) {
            decorViews.values.forEach { it.drawState.invalidateMaskCapture() }
        }
    }

    override fun isActive(): Boolean {
        return isSessionReplayActive
    }

    /**
     * Called when an event is captured. Checks if the event matches any configured triggers
     * and starts session recording if so.
     */
    override fun onEvent(
        event: String,
        properties: Map<String, Any>?,
    ) {
        val postHog = this.postHog ?: return

        val currentSessionId = postHog.getSessionId()?.toString() ?: return

        val triggers = config.remoteConfigHolder?.getEventTriggers()

        // No triggers configured, nothing to do
        if (triggers.isNullOrEmpty()) {
            return
        }

        // Check if this session has already been activated
        val activatedSession = synchronized(eventTriggersLock) { triggerActivatedSessionId }
        if (activatedSession == currentSessionId) {
            return
        }

        // Check if the event matches any trigger
        if (triggers.contains(event)) {
            synchronized(eventTriggersLock) {
                triggerActivatedSessionId = currentSessionId
            }
            config.logger.log("[Session Replay] Event trigger matched: $event. Starting replay for session $currentSessionId.")
            // Start the integration now that a trigger has matched
            start(resumeCurrent = true)
        }
    }

    /**
     * Called when the session ID changes. Stops recording if event triggers are configured
     * and the new session hasn't been activated yet, or re-initialises recording so the
     * new session gets fresh meta + full wireframe events.
     */
    override fun onSessionIdChanged() {
        if (this.postHog == null) return

        // Read-only: getActiveSessionId() can rotate the session and would re-fire this listener.
        val currentSessionId = PostHogSessionManager.peekSessionId()?.toString()

        resetSessionStateIfNeeded(currentSessionId)

        val remoteConfig = config.remoteConfigHolder
        val triggers = remoteConfig?.getEventTriggers()

        val activatedSession = synchronized(eventTriggersLock) { triggerActivatedSessionId }

        if (!triggers.isNullOrEmpty() && activatedSession != currentSessionId) {
            if (isSessionReplayActive) {
                config.logger.log("[Session Replay] Session changed. Stopping until trigger is matched.")
                stopRecording()
            }
            return
        }

        // The listener can fire from any thread that calls capture(); replay state writes
        // (snapshot WeakHashMap, isSessionReplayActive) must happen on main.
        if (currentSessionId == null) {
            if (isSessionReplayActive) {
                config.logger.log("[Session Replay] Session cleared. Stopping recording.")
                mainHandler.handler.post { stopRecording() }
            }
            return
        }

        // Run regardless of isSessionReplayActive: the prior session may have been sampled out
        // and the new one may now pass. Sampling is re-evaluated for the (already-current)
        // session without rotating the id (the silent rotation that fired this already
        // rotated; going through PostHog.startSessionReplay(false) would double-rotate).
        config.logger.log("[Session Replay] Session changed. Re-initializing recording for new session.")
        mainHandler.handler.post {
            // config.sessionReplay controls automatic starts. A recording started while it was
            // off must survive rotation, so it is preserved here too.
            if (!config.sessionReplay && !startedWithAutomaticDisabled) {
                if (isSessionReplayActive) stopRecording()
                return@post
            }
            if (remoteConfig?.isSessionReplayFlagActive() != true) {
                if (isSessionReplayActive) stopRecording()
                return@post
            }
            if (remoteConfig.makeSamplingDecision(currentSessionId).not()) {
                if (isSessionReplayActive) stopRecording()
                return@post
            }
            if (isSessionReplayActive) stopRecording()
            start(resumeCurrent = false)
        }
    }

    /**
     * Returns true if event triggers are configured and the current session has not been activated yet.
     */
    private fun shouldWaitForEventTriggers(): Boolean {
        val postHog = this.postHog ?: return false

        val currentSessionId = postHog.getSessionId()?.toString() ?: return false

        val triggers = config.remoteConfigHolder?.getEventTriggers()

        // No triggers configured, don't wait
        if (triggers.isNullOrEmpty()) {
            return false
        }

        // Check if this session has been activated
        val activatedSession = synchronized(eventTriggersLock) { triggerActivatedSessionId }
        return activatedSession != currentSessionId
    }

    private fun resetSessionStateIfNeeded(
        currentSessionId: String?,
        force: Boolean = false,
    ) {
        if (!force && replaySessionId == currentSessionId) {
            return
        }

        replaySessionId = currentSessionId
        clearSnapshotStates()
        resetBufferingState()
    }

    // MARK: - PostHogReplayBufferDelegate

    private val isBuffering: Boolean
        get() {
            synchronized(bufferingLock) {
                if (awaitingFirstRemoteConfig) {
                    return true
                }
                val minimumDuration = cachedMinimumDurationMs
                if (minimumDuration == null || minimumDuration <= 0) {
                    return false
                }
                return !hasPassedMinimumDuration
            }
        }

    private fun onReplayBufferSnapshot(replayQueue: PostHogReplayQueue) {
        // The min-duration migrate is triggered by elapsed time, independently of isBuffering's
        // add-routing. Don't drain the buffer into the persisted queue until the first remote config
        // has decided — otherwise a cached minimumDuration could migrate stale-cache snapshots before
        // the flag decision and leak them to the network.
        val awaiting = synchronized(bufferingLock) { awaitingFirstRemoteConfig }
        if (awaiting) {
            return
        }

        // Only persist while recording is active: on a fresh-false resolve the buffer is cleared, and
        // this stops an in-flight add() that began buffering before the flag flipped from migrating the
        // stale window into the persisted queue (the migrate path bypasses the add() routing gate).
        if (!isActive()) {
            return
        }

        migrateBufferIfMinimumDurationMet(replayQueue)
    }

    /**
     * Migrates the buffer to the persisted queue when it should flush now — no minimum duration is
     * configured, or the buffered window (oldest to newest) already spans it — and otherwise leaves
     * it buffering for the minimum-duration window. Each caller gates recording first: the snapshot
     * callback on [isActive], the first-config resolve on [isRecordingPermittedForCurrentSession].
     */
    private fun migrateBufferIfMinimumDurationMet(replayQueue: PostHogReplayQueue) {
        val minimumDurationMs = synchronized(bufferingLock) { cachedMinimumDurationMs }

        if (minimumDurationMs == null || minimumDurationMs <= 0) {
            synchronized(bufferingLock) { hasPassedMinimumDuration = true }
            migrateBufferToQueueOnBackgroundThread(replayQueue)
            return
        }

        // Session replay payloads include metadata snapshots required by the player, so migration is
        // all-or-nothing once the buffered window spans the minimum duration.
        if ((replayQueue.bufferDurationMs ?: 0) >= minimumDurationMs) {
            config.logger.log(
                "[Session Replay] Minimum duration met. Migrating ${replayQueue.bufferDepth} buffered events to replay queue.",
            )
            // Flip state before migration so new snapshots don't keep entering the buffer during long-running migrations.
            synchronized(bufferingLock) { hasPassedMinimumDuration = true }
            migrateBufferToQueueOnBackgroundThread(replayQueue)
        }
    }

    private fun migrateBufferToQueueOnBackgroundThread(replayQueue: PostHogReplayQueue) {
        try {
            executor.submit {
                try {
                    replayQueue.migrateBufferToQueue()
                } catch (e: Throwable) {
                    config.logger.log("Session Replay migrateBufferToQueue failed: $e.")
                }
            }
        } catch (e: Throwable) {
            config.logger.log("Session Replay scheduling migrateBufferToQueue failed: $e.")
        }
    }

    // MARK: - Remote Config

    override fun onRemoteConfig(loaded: Boolean) {
        if (!loaded) {
            // The first remote config attempt failed (offline/error), so no live config will arrive
            // this resolution. Fall back to the disk-cached flag via resolveFirstRemoteConfig: keep
            // recording (migrate the buffered window) when the cache permits it, or drop the buffer
            // and stop when it doesn't. Recording for offline-first apps is preferred over discarding
            // a session whose cached flag was on. Log only while genuinely falling back;
            // resolveFirstRemoteConfig re-checks the gate and no-ops if the first config already resolved.
            val resolving = synchronized(bufferingLock) { awaitingFirstRemoteConfig }
            if (resolving) {
                config.logger.log("[Session Replay] First remote config fetch failed. Falling back to the cached session replay flag.")
            }
            resolveFirstRemoteConfig()
            return
        }

        updateCachedMinimumDuration()
        // Snapshot the gate state BEFORE resolveFirstRemoteConfig disarms it, so reevaluateRecordingState
        // can tell whether this callback is the first delivery. loadRemoteConfig loads /flags nested and
        // synchronously (notifyRemoteConfigLoaded=false) before this single callback fires, so the
        // session-replay flag is already fresh here — resolve the buffer against it on this delivery
        // rather than deferring to a second callback the cold-start path never produces.
        val wasFirstDelivery = synchronized(bufferingLock) { awaitingFirstRemoteConfig }
        resolveFirstRemoteConfig()
        reevaluateRecordingState(isFirstDelivery = wasFirstDelivery)
    }

    /**
     * Resolves the buffer once the first remote config settles — either a live response, or a
     * terminal fetch failure (onRemoteConfig with loaded = false) that falls back to the disk-cached
     * flag — then disarms the gate. When the session is recordable now, the buffered opening window is
     * handed to the minimum-duration gate (kept buffering, not force-flushed) so a short session
     * isn't persisted just because the flag resolved; otherwise — flag off, master switch off, or
     * the fresh config sampling this session out — the capturer is stopped and the buffer dropped
     * before disarming, so an in-flight `add()` (which checks [isBuffering] and enqueues in separate
     * steps, not atomically with the disarm) can't route a stale snapshot to the persisted queue.
     */
    private fun resolveFirstRemoteConfig() {
        val generation =
            synchronized(bufferingLock) {
                if (!awaitingFirstRemoteConfig) {
                    return
                }
                bufferingGeneration
            }

        if (isRecordingPermittedForCurrentSession()) {
            // Disarm and migrate only if a session rotation hasn't re-armed the gate under a newer
            // generation in the meantime; that generation owns the (re-cleared) buffer and resolves
            // it on its own delivery, so acting here would migrate the wrong session's window.
            val resolved =
                synchronized(bufferingLock) {
                    if (bufferingGeneration != generation || !awaitingFirstRemoteConfig) {
                        false
                    } else {
                        awaitingFirstRemoteConfig = false
                        true
                    }
                }
            if (resolved) {
                replayQueue?.let { migrateBufferIfMinimumDurationMet(it) }
            }
        } else {
            // Bail if a session rotation superseded this resolution before we touch the buffer.
            val superseded =
                synchronized(bufferingLock) {
                    bufferingGeneration != generation || !awaitingFirstRemoteConfig
                }
            if (superseded) {
                return
            }
            // Self-gate the capturer first so no new snapshot re-enters the buffer, then drop the
            // buffer, and only then disarm — an in-flight add() checks isBuffering and enqueues in
            // separate steps, so it could otherwise route a stale snapshot past the disarm.
            stopRecording()
            replayQueue?.clearBuffer()
            synchronized(bufferingLock) {
                if (bufferingGeneration == generation) {
                    awaitingFirstRemoteConfig = false
                }
            }
        }
    }

    /**
     * Whether the live (or, on a fetch failure, disk-cached) remote config permits recording the
     * current session right now. Mirrors the decision [reevaluateRecordingState] makes — automatic
     * start setting, session-replay flag, event triggers, and the sampling decision — so the buffered
     * opening window is migrated only when the session is genuinely recordable, never for one the
     * fresh config samples out.
     */
    private fun isRecordingPermittedForCurrentSession(): Boolean {
        val remoteConfig = config.remoteConfigHolder ?: return false
        if ((!config.sessionReplay && !startedWithAutomaticDisabled) || !remoteConfig.isSessionReplayFlagActive()) {
            return false
        }
        if (shouldWaitForEventTriggers()) {
            return false
        }
        val currentSessionId = postHog?.getSessionId()?.toString() ?: return false
        return remoteConfig.makeSamplingDecision(currentSessionId)
    }

    /**
     * Re-evaluate recording against the live remote config: preserve a manual recording when
     * automatic replay is off, stop when the project flag turns off or the session is sampled out,
     * and automatically resume only when automatic replay is enabled. Without this, a fresh-`false`
     * would keep recording until the next session rotation.
     *
     * The very first delivery is exempt from the **flag-off** stop only: on that delivery
     * [resolveFirstRemoteConfig] already owns the stop-and-drop decision for the buffered opening
     * window, so stopping again here would be redundant. Sampling is deterministic per session id,
     * so the sampled-out stop is NOT exempt.
     */
    private fun reevaluateRecordingState(isFirstDelivery: Boolean = false) {
        val postHog = this.postHog ?: return
        val remoteConfig = config.remoteConfigHolder ?: return

        if ((!config.sessionReplay && !startedWithAutomaticDisabled) || !remoteConfig.isSessionReplayFlagActive()) {
            if (!isFirstDelivery) {
                stopIfActive("Remote config disabled recording. Stopping.")
            }
            return
        }

        if (shouldWaitForEventTriggers()) {
            return
        }

        val currentSessionId = postHog.getSessionId()?.toString() ?: return
        if (!remoteConfig.makeSamplingDecision(currentSessionId)) {
            stopIfActive("Remote config sampled this session out. Stopping.")
            return
        }

        if (!isSessionReplayActive) {
            config.logger.log("[Session Replay] Remote config enabled recording. Resuming.")
            mainHandler.handler.post {
                if (!isSessionReplayActive) {
                    // Force a fresh keyframe for the resumed segment. While stopped, per-view snapshot
                    // state is frozen and can reference a full snapshot that was never delivered (e.g. a
                    // first-config-off opening window that was dropped), so resuming against it would emit
                    // orphaned incremental snapshots the player can't anchor. Clear the state and force a
                    // redraw so the resumed segment starts with meta + full snapshot — without rotating the
                    // session or touching the cold-start buffering state (unlike start(resumeCurrent = false)).
                    clearSnapshotStates()
                    start(resumeCurrent = true)
                    synchronized(decorViews) {
                        decorViews.keys.forEach { it.postInvalidate() }
                    }
                }
            }
        }
    }

    private fun stopIfActive(reason: String) {
        if (isSessionReplayActive) {
            config.logger.log("[Session Replay] $reason")
            // Flip the active gate synchronously so a concurrent add() on the replay executor stops
            // persisting immediately (PostHogReplayQueue.shouldPersist reads isActive), instead of
            // leaking snapshots to the send queue in the window before the posted stopRecording() runs on main.
            isSessionReplayActive = false
            mainHandler.handler.post { stopRecording() }
        }
    }

    // Whether to buffer until the live remote config resolves: only when a remote config holder
    // exists and hasn't resolved yet, AND a fetch will actually be attempted at setup. If neither
    // remoteConfig nor preloadFeatureFlags is enabled, PostHog.setup dispatches no /config or /flags
    // request, so no onRemoteConfig callback ever arrives to disarm the gate —
    // buffering would then grow unbounded for the whole session. Don't arm in that case.
    private fun shouldAwaitFirstRemoteConfig(): Boolean {
        @Suppress("DEPRECATION")
        if (!config.remoteConfig && !config.preloadFeatureFlags) {
            return false
        }
        return config.remoteConfigHolder?.let { !it.hasRemoteConfigFetched() } ?: false
    }

    private fun updateCachedMinimumDuration() {
        val minimumDuration = config.remoteConfigHolder?.getRecordingMinimumDurationMs()
        synchronized(bufferingLock) {
            cachedMinimumDurationMs = minimumDuration
        }
    }

    // MARK: - Buffering State

    /**
     * Resets buffering state for a new session — clears the buffer and marks
     * as not yet passed minimum duration.
     */
    private fun resetBufferingState() {
        synchronized(bufferingLock) {
            hasPassedMinimumDuration = false
            // Re-arm after reset()/identity change (PostHogRemoteConfig.clear() cleared its fetched
            // flag); a plain session rotation keeps it disarmed since the config is still fetched.
            awaitingFirstRemoteConfig = shouldAwaitFirstRemoteConfig()
            // Supersede any in-flight resolveFirstRemoteConfig so it can't migrate or clear this
            // session's buffer after we've re-armed and cleared it below.
            bufferingGeneration++
        }
        // Clear any buffered events from previous session
        replayQueue?.clearBuffer()
    }

    internal companion object {
        const val PH_NO_CAPTURE_LABEL: String = "ph-no-capture"
        const val PH_NO_MASK_LABEL: String = "ph-no-mask"
        const val ANDROID_COMPOSE_VIEW_CLASS_NAME: String = "androidx.compose.ui.platform.AndroidComposeView"
        const val ANDROID_COMPOSE_VIEW: String = "AndroidComposeView"

        // Pre-walk re-arm attempts per capture: a screen that redraws during every attempt
        // discards this tick and retries at the next scheduled snapshot.
        private const val MAX_BASELINE_ARM_ATTEMPTS: Int = 3

        private val integrationInstalled = AtomicBoolean(false)
    }
}

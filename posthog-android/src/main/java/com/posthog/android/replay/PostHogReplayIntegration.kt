package com.posthog.android.replay

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Point
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.VectorDrawable
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.text.InputType
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.FLAG_SECURE
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.posthog.PostHogEventName
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
import com.posthog.android.replay.internal.PixelCopyBitmapBuffer
import com.posthog.android.replay.internal.ViewTreeSnapshotStatus
import com.posthog.android.replay.internal.WindowDrawState
import com.posthog.android.replay.internal.downscaledWebpBase64
import com.posthog.android.replay.internal.isAlive
import com.posthog.android.replay.internal.isAliveAndAttachedToWindow
import com.posthog.android.replay.internal.resolveScreenshotScale
import com.posthog.android.replay.internal.scaledScreenshotDimension
import com.posthog.android.replay.internal.twoStageCopySize
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
import com.posthog.internal.replay.RRWireframe
import com.posthog.internal.replay.capture
import curtains.Curtains
import curtains.DispatchState
import curtains.OnRootViewsChangedListener
import curtains.TouchEventInterceptor
import curtains.onDecorViewReady
import curtains.phoneWindow
import curtains.touchEventInterceptors
import curtains.windowAttachCount
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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

    // Guarded by decorViews, together with snapshot-state commits. Stop/resume in the same
    // session must invalidate work too, so session identity alone is not a sufficient lease.
    private var snapshotGeneration: Long = 0

    // Scene state is committed under decorViews, like the existing per-root snapshot history.
    private val sceneImages = WeakHashMap<View, SceneImage>()
    private val sceneDirtyViews = Collections.newSetFromMap(IdentityHashMap<View, Boolean>())
    private val sceneIds = WeakHashMap<View, Int>()
    private var nextSceneId = FIRST_SCENE_IMAGE_ID
    private var lastScene: List<RRWireframe>? = null
    private var sceneViewport: Pair<Int, Int>? = null

    private data class SceneLayer(
        val view: View,
        val window: Window,
        val status: ViewTreeSnapshotStatus,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val dimAmount: Float,
        val windowAlpha: Float,
    )

    private data class SceneImage(
        val wireframe: RRWireframe,
        val windowWidth: Int,
        val windowHeight: Int,
        val windowAlpha: Float,
        val bitmapConfig: Bitmap.Config,
        val maskAllTextInputs: Boolean,
        val maskAllImages: Boolean,
        val maskRects: List<Rect>?,
        val secure: Boolean,
    )

    private data class Screenshot(
        val wireframe: RRWireframe,
        val maskRects: List<Rect>?,
        val secure: Boolean = false,
    )

    private data class SceneMetadata(
        val viewport: Pair<Int, Int>,
        val title: String,
        val keyboardVisible: Boolean,
        val keyboardEvent: RRCustomEvent?,
    )

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
    private val pixelCopyBitmapBuffer = PixelCopyBitmapBuffer()

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

    // Set only by an explicit start request made while config.sessionReplay is false. It survives
    // stopRecording() so an internal stop (session cleared, sampled out, flag off) can still resume
    // later; only an explicit stop() or uninstall() clears it.
    @Volatile
    private var startedWithAutomaticDisabled: Boolean = false

    // Event triggers for session recording
    private val eventTriggersLock = Any()

    @Volatile
    private var triggerActivatedSessionId: String? = null

    // flutter captures snapshots, so we don't need to capture them here
    private val isNativeSdk: Boolean
        get() = (config.sdkName != "posthog-flutter")

    @Volatile
    private var postHog: PostHogInterface? = null

    @Volatile
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
    private val drawSampleWalk = MaskWalk(reuseTextLayouts = true)

    internal fun onDrawCallback(
        view: View,
        drawState: WindowDrawState,
    ) {
        // Keep this first so a draw that overlaps the verified pre-walk cannot go unnoticed.
        drawState.recordDraw()
        // A text style change only shows up in a new layout result, and it has to draw to reach pixels.
        textPaintedBoundsCache.clear()

        val classifyLegacyDraw =
            !shouldVerifyMaskAlignment(view, drawState) ||
                drawState.isLegacyCaptureActive
        if (classifyLegacyDraw) {
            val isOnlyAnimationRedraw =
                !drawState.didLayoutSinceReset &&
                    // The surface scan walks the whole tree, so it runs only while a legacy
                    // capture can read the verdict; beginLegacyCapture resets it anyway.
                    (
                        view.hasTransientState() ||
                            (drawState.isLegacyCaptureActive && view.hasActiveSurfaceRendering())
                    ) &&
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

                                        requestSceneCapture(decorView, drawState)
                                    }

                                val layoutListener =
                                    ViewTreeObserver.OnGlobalLayoutListener {
                                        // Invalidate immediately; the following draw requests the capture
                                        // through the same throttle as other ordinary view updates.
                                        drawState.recordLayout()
                                    }
                                decorView.viewTreeObserver?.addOnGlobalLayoutListener(layoutListener)

                                val status = ViewTreeSnapshotStatus(listener, layoutListener, drawState = drawState)
                                decorViews[decorView] = status
                                if (isActive() && isNativeSdk) {
                                    mainHandler.handler.post { requestSceneCapture(decorView, drawState) }
                                }
                            } catch (e: Throwable) {
                                config.logger.log("Session Replay onDecorViewReady failed: $e.")
                            }
                        }

                        window.touchEventInterceptors += touchInterceptor(window)
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

    // Acquire before submitting, not on the worker: a busy worker must not accumulate
    // redundant captures. Draw-time mask verification still runs for every draw.
    internal fun submitCapture(
        drawState: WindowDrawState,
        onAvailable: (() -> Unit)? = null,
        capture: () -> Unit,
    ): Boolean {
        if (!drawState.tryScheduleCapture(onAvailable)) return false
        try {
            executor.submit {
                try {
                    capture()
                } finally {
                    drawState.finishScheduledCapture()
                }
            }
        } catch (e: Throwable) {
            drawState.finishScheduledCapture()
            config.logger.log("Session Replay capture submission failed: $e.")
            return false
        }
        return true
    }

    private fun requestSceneCapture(
        changedView: View? = null,
        drawState: WindowDrawState? = null,
    ) {
        try {
            if (!isNativeSdk) return
            val generation =
                synchronized(decorViews) {
                    if (!isActive()) return
                    if (changedView != null) {
                        val status = decorViews[changedView] ?: return
                        if (drawState != null && status.drawState !== drawState) return
                        sceneDirtyViews.add(changedView)
                    }
                    snapshotGeneration
                }
            // Pin the lease when selecting the topology, before any work can queue behind it.
            val layers = selectSceneLayers() ?: return
            val base = layers.first()
            submitCapture(
                base.status.drawState,
                onAvailable = { mainHandler.handler.post { requestSceneCapture() } },
            ) {
                try {
                    generateSceneSnapshot(layers, generation)
                } catch (e: Throwable) {
                    config.logger.log("Session Replay scene capture failed: $e.")
                }
            }
        } catch (e: Throwable) {
            config.logger.log("Session Replay scene capture request failed: $e.")
        }
    }

    private fun Context.activityOwner(): Activity? {
        var current: Context = this
        repeat(10) {
            if (current is Activity) return current
            current = (current as? ContextWrapper)?.baseContext ?: return null
        }
        return null
    }

    internal fun isSceneWindow(
        baseToken: IBinder,
        baseOwner: Activity?,
        params: WindowManager.LayoutParams,
        window: Window,
    ): Boolean = params.token == baseToken || (baseOwner != null && window.context.activityOwner() === baseOwner)

    // Curtains' root list is read on main, after its attachment callback completes.
    // A dialog can attach before its activity decor. On older Android versions its
    // public window token can differ from the activity's, so use the owning Activity
    // as well as the window token to associate ordinary PhoneWindow dialogs.
    private fun selectSceneLayers(): List<SceneLayer>? {
        val roots = Curtains.rootViews
        val base =
            roots.lastOrNull {
                it.isAliveAndAttachedToWindow() && it.isShown &&
                    (it.layoutParams as? WindowManager.LayoutParams)?.type == WindowManager.LayoutParams.TYPE_BASE_APPLICATION
            } ?: return null
        val token = (base.layoutParams as? WindowManager.LayoutParams)?.token ?: return null
        val displayId = base.display?.displayId
        val owner = base.phoneWindow?.context?.activityOwner()
        val layers = mutableListOf<SceneLayer>()
        for (view in listOf(base) + roots.filter { it !== base }) {
            if (!view.isAliveAndAttachedToWindow() || !view.isShown || view.display?.displayId != displayId) continue
            val params = view.layoutParams as? WindowManager.LayoutParams ?: continue
            if (view !== base && params.type != WindowManager.LayoutParams.TYPE_APPLICATION) continue
            val window = view.phoneWindow ?: continue
            if (view !== base && !isSceneWindow(token, owner, params, window)) {
                continue
            }
            val status = decorViews[view] ?: return null
            if (view.width <= 0 || view.height <= 0) return null
            val position = IntArray(2)
            view.getLocationOnScreen(position)
            val attributes = window.attributes
            layers.add(
                SceneLayer(
                    view,
                    window,
                    status,
                    position[0],
                    position[1],
                    view.width,
                    view.height,
                    if (attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) attributes.dimAmount else 0f,
                    if (view !== base) attributes.alpha else 1f,
                ),
            )
        }
        return layers.takeIf { it.firstOrNull()?.view === base }
    }

    private fun generateSceneSnapshot(
        layers: List<SceneLayer>,
        generation: Long,
    ): Boolean {
        val postHog = postHog ?: return false
        val sessionId = PostHogSessionManager.getActiveSessionId()?.toString() ?: return false
        synchronized(decorViews) {
            if (!isActive() || replaySessionId != sessionId || snapshotGeneration != generation) return false
        }
        val timestamp = config.dateProvider.currentTimeMillis()
        val colorMode = config.sessionReplayConfig.screenshotColorMode
        val maskAllTextInputs = config.sessionReplayConfig.maskAllTextInputs
        val maskAllImages = config.sessionReplayConfig.maskAllImages
        val scene = mutableListOf<RRWireframe>()
        val sceneMasks = IdentityHashMap<View, List<Rect>>()
        val unmaskedWindows = mutableListOf<Window>()

        fun maskingSettingsChanged(): Boolean =
            config.sessionReplayConfig.maskAllTextInputs != maskAllTextInputs ||
                config.sessionReplayConfig.maskAllImages != maskAllImages

        fun restoreDirty(layer: SceneLayer) {
            synchronized(decorViews) {
                if (isActive() && snapshotGeneration == generation && decorViews[layer.view] === layer.status) {
                    sceneDirtyViews.add(layer.view)
                }
            }
        }
        for (layer in layers) {
            val (id, cached, dirty) =
                synchronized(decorViews) {
                    val id = sceneIds.getOrPut(layer.view) { nextSceneId++ }
                    Triple(id, sceneImages[layer.view], sceneDirtyViews.remove(layer.view))
                }
            val isDialog = layer !== layers.first()
            val bitmapConfig =
                if (colorMode == PostHogScreenshotColorMode.RGB_565 && !isDialog) {
                    Bitmap.Config.RGB_565
                } else {
                    Bitmap.Config.ARGB_8888
                }
            // Older dialog surfaces cannot be aligned with decor-coordinate masks. Recheck
            // privacy on every scene capture rather than reusing a previously unmasked image.
            val recheckDialogMasks = isDialog && Build.VERSION.SDK_INT < Build.VERSION_CODES.P
            val cacheCandidate =
                cached?.takeIf {
                    !recheckDialogMasks && !dirty &&
                        it.windowWidth == layer.width && it.windowHeight == layer.height &&
                        it.windowAlpha == layer.windowAlpha && it.bitmapConfig == bitmapConfig &&
                        it.maskAllTextInputs == maskAllTextInputs && it.maskAllImages == maskAllImages
                }
            // View tags and semantics can change without a draw. Validate masks only when
            // considering reuse; fresh captures supply their actual painted mask baseline.
            val reuseImage =
                if (cacheCandidate != null) {
                    runOnMainThreadBlocking {
                        if (cacheCandidate.secure != layer.window.isSecure()) {
                            false
                        } else if (cacheCandidate.secure) {
                            true
                        } else {
                            val maskRects = layer.view.collectSceneMaskRects() ?: return@runOnMainThreadBlocking null
                            cacheCandidate.maskRects == maskRects
                        }
                    } ?: run {
                        restoreDirty(layer)
                        return false
                    }
                } else {
                    false
                }
            val image =
                if (cacheCandidate != null && reuseImage) {
                    Screenshot(cacheCandidate.wireframe, cacheCandidate.maskRects, cacheCandidate.secure)
                } else {
                    val drawState = layer.status.drawState
                    // The Activity owns the scene worker; dialogs also need their own copy gate.
                    if (isDialog &&
                        !drawState.tryScheduleCapture {
                            mainHandler.handler.post { requestSceneCapture() }
                        }
                    ) {
                        restoreDirty(layer)
                        return false
                    }
                    try {
                        layer.view.captureScreenshot(
                            layer.window,
                            drawState,
                            isDialog = isDialog,
                            bitmapConfig = bitmapConfig,
                            windowAlpha = layer.windowAlpha,
                        )
                            ?: run {
                                restoreDirty(layer)
                                return false
                            }
                    } finally {
                        if (isDialog) drawState.finishScheduledCapture()
                    }
                }
            val positioned =
                image.wireframe.copy(
                    id = id,
                    x = layer.x.densityValue(screenDensity),
                    y = layer.y.densityValue(screenDensity),
                    width = layer.width.densityValue(screenDensity),
                    height = layer.height.densityValue(screenDensity),
                )
            val cachedImage =
                runOnMainThreadBlocking {
                    synchronized(decorViews) {
                        if (!isActive() || snapshotGeneration != generation ||
                            PostHogSessionManager.peekSessionId()?.toString() != sessionId ||
                            decorViews[layer.view] !== layer.status || !layer.view.isShown ||
                            layer.view.width != layer.width || layer.view.height != layer.height || maskingSettingsChanged() ||
                            (!image.secure && layer.window.isSecure()) ||
                            (image.maskRects != null && layer.view.collectSceneMaskRects() != image.maskRects)
                        ) {
                            return@runOnMainThreadBlocking false
                        }
                        // Keep each validated image even if a later layer fails. Draws arriving after
                        // this layer's dirty flag was consumed remain pending for the next capture.
                        sceneImages[layer.view] =
                            SceneImage(
                                positioned,
                                layer.width,
                                layer.height,
                                layer.windowAlpha,
                                bitmapConfig,
                                maskAllTextInputs,
                                maskAllImages,
                                image.maskRects,
                                image.secure,
                            )
                        true
                    }
                }
            if (cachedImage != true) {
                restoreDirty(layer)
                return false
            }
            scene.add(positioned)
            image.maskRects?.let { sceneMasks[layer.view] = it }
            if (!image.secure) unmaskedWindows.add(layer.window)
        }

        // Android applies the topmost requesting window's dim to the scene beneath it.
        val dimIndex = layers.indexOfLast { it.dimAmount > 0f }
        if (dimIndex >= 0) {
            val base = scene.first()
            // 4.0 wireframes carry no style, so the dim is a small translucent image the player stretches.
            dimScreenshotBase64(layers[dimIndex].dimAmount)?.let { dim ->
                scene.add(
                    dimIndex,
                    RRWireframe(
                        id = DIM_LAYER_ID,
                        x = base.x,
                        y = base.y,
                        width = base.width,
                        height = base.height,
                        type = "screenshot",
                        base64 = dim,
                    ),
                )
            }
        }
        // Do not hold the producer lock while waiting for main: start/stop also need it.
        val metadata =
            runOnMainThreadBlocking {
                val base = layers.first()
                val screenSize = base.view.context.screenSize() ?: return@runOnMainThreadBlocking null
                val viewport = screenSize.width to screenSize.height
                synchronized(decorViews) {
                    if (!isActive() || snapshotGeneration != generation ||
                        PostHogSessionManager.peekSessionId()?.toString() != sessionId ||
                        layers.any { layer ->
                            decorViews[layer.view] !== layer.status || !layer.view.isShown ||
                                layer.view.width != layer.width || layer.view.height != layer.height
                        } || layers.drop(1).any { it.window.attributes.alpha != it.windowAlpha } || maskingSettingsChanged() ||
                        unmaskedWindows.any { it.isSecure() } ||
                        sceneMasks.any { (view, rects) -> view.collectSceneMaskRects() != rects }
                    ) {
                        return@runOnMainThreadBlocking null
                    }
                    val title = base.window.attributes.title?.toString()?.substringAfter("/") ?: ""
                    val (visible, keyboardEvent) = detectKeyboardVisibility(base.view, base.status.keyboardVisible)
                    SceneMetadata(viewport, title, visible, keyboardEvent)
                }
            } ?: return false
        // Only the worker that received the result may consume snapshot history. A main-thread
        // task can finish after its waiter times out; it must leave the keyframe available to retry.
        val events =
            synchronized(decorViews) {
                if (!isActive() || snapshotGeneration != generation ||
                    PostHogSessionManager.peekSessionId()?.toString() != sessionId ||
                    layers.any { decorViews[it.view] !== it.status } || maskingSettingsChanged()
                ) {
                    return false
                }
                val result = mutableListOf<RREvent>()
                val sceneChanged = lastScene != scene
                if (sceneViewport != metadata.viewport || sceneChanged) {
                    result.add(
                        RRMetaEvent(
                            width = metadata.viewport.first,
                            height = metadata.viewport.second,
                            timestamp = timestamp,
                            href = metadata.title,
                        ),
                    )
                    sceneViewport = metadata.viewport
                }
                if (sceneChanged) {
                    result.add(RRFullSnapshotEvent(scene, 0, 0, timestamp))
                    lastScene = scene
                }
                layers.first().status.keyboardVisible = metadata.keyboardVisible
                metadata.keyboardEvent?.let { result.add(it) }
                result
            }
        captureSnapshotEvents(postHog, events, sessionId)
        return true
    }

    // Called on main, including for cached windows. A partial or failed walk cannot
    // establish that a previously redacted image is still safe to reuse.
    private fun View.collectSceneMaskRects(): List<Rect>? {
        if (isRenderedButUnplaceable()) return null
        val walk = MaskWalk()
        findMaskableWidgets(this, walk)
        return walk.rects.takeUnless { walk.poisoned || walk.aborted }
    }

    private val onRootViewsChangedListener =
        OnRootViewsChangedListener { view, added ->
            addView(view, added)
            if (isActive() && isNativeSdk) {
                // Curtains notifies before it updates rootViews. Read the completed topology later.
                mainHandler.handler.post { requestSceneCapture() }
            }
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

    // Window callbacks add these on the main thread; install() and uninstall() remove them on the caller's.
    private val touchInterceptors = Collections.synchronizedMap(WeakHashMap<Window, TouchEventInterceptor>())

    // A FLAG_SECURE window is recorded fully masked, so its touches aren't recorded either.
    // The flag is read per touch because apps toggle it at runtime.
    internal fun touchInterceptor(window: Window): TouchEventInterceptor =
        touchInterceptors.getOrPut(window) {
            val windowRef = WeakReference(window)
            val gesture = TouchGesture()
            TouchEventInterceptor { motionEvent, dispatch ->
                val currentWindow = windowRef.get()
                if (currentWindow?.isSecure() == true) {
                    dispatch(motionEvent)
                } else {
                    interceptTouch(motionEvent, dispatch, currentWindow?.peekDecorView(), gesture)
                }
            }
        }

    private val listenerGesture = TouchGesture()

    internal val onTouchEventListener =
        TouchEventInterceptor { motionEvent, dispatch ->
            interceptTouch(motionEvent, dispatch, null, listenerGesture)
        }

    // Main-thread only. Once a gesture starts inside an explicit mask, its later events stay
    // unrecorded even if the masked view is gone, so the TouchEnd can't reveal the key.
    private class TouchGesture {
        var masked = false
    }

    private fun interceptTouch(
        motionEvent: MotionEvent,
        dispatch: (MotionEvent) -> DispatchState,
        decorView: View?,
        gesture: TouchGesture,
    ): DispatchState {
        val action = motionEvent.action.and(MotionEvent.ACTION_MASK)
        // Decided before dispatch, while the view tree still shows what was touched.
        val record = shouldRecordTouch(action, motionEvent, decorView, gesture)
        try {
            val state = dispatch(motionEvent)
            try {
                if (!record) {
                    return state
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
                        when (action) {
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
            return state
        } catch (e: Throwable) {
            config.logger.log("TouchEventInterceptor $motionEvent failed: $e.")
            throw e
        }
    }

    private fun shouldRecordTouch(
        action: Int,
        motionEvent: MotionEvent,
        decorView: View?,
        gesture: TouchGesture,
    ): Boolean {
        val gestureEnds = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
        try {
            if (!config.sessionReplayConfig.captureTouches || !isActive()) {
                gesture.masked = false
                return false
            }
            if (action == MotionEvent.ACTION_DOWN) {
                gesture.masked = decorView?.isTouchInsideExplicitMask(motionEvent) == true
            }
            val masked =
                gesture.masked ||
                    (action == MotionEvent.ACTION_UP && decorView?.isTouchInsideExplicitMask(motionEvent) == true)
            return !masked
        } catch (e: Throwable) {
            gesture.masked = action == MotionEvent.ACTION_DOWN || gesture.masked
            return false
        } finally {
            if (gestureEnds) {
                gesture.masked = false
            }
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
            touchInterceptors.remove(window)?.let { window.touchEventInterceptors -= it }
        }

        decorViews.remove(view)
        synchronized(decorViews) {
            sceneImages.remove(view)
            sceneDirtyViews.remove(view)
        }
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
            stopRecording()
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
            try {
                synchronized(pixelCopyBitmapBuffer) {
                    synchronized(decorViews) {
                        startedWithAutomaticDisabled = false
                        isSessionReplayActive = false
                        snapshotGeneration++
                        pixelCopyBitmapBuffer.close()
                    }
                }
            } catch (e: Throwable) {
                config.logger.log("Session Replay screenshot buffer cleanup failed: $e.")
            }
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
     * must treat that, not the return value, as the retry signal. Returns false without
     * calling [onResult] if this window already has a pending/in-flight capture.
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
            val drawState = decorViews[decorView]?.drawState
            if (drawState == null) {
                // Not tracked yet (onDecorViewReady pending): generateSnapshot
                // would bail silently — report failure so the caller retries
                // and the first-of-episode reset is not consumed.
                return false
            }
            return submitCapture(drawState) {
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
                    if (!isStillValid() || decorViews[decorView]?.drawState !== drawState) {
                        // The contract promises onResult for every scheduled
                        // capture; a silent self-drop would leave the caller's
                        // in-flight tracking latched forever.
                        report(false)
                        return@submitCapture
                    }
                    if (forceFullSnapshot) {
                        synchronized(decorViews) {
                            decorViews[decorView]?.let { status ->
                                status.sentFullSnapshot = false
                                status.sentMetaEvent = false
                                status.lastSnapshot = null
                            }
                        }
                    }
                    val delivered = generateSnapshot(WeakReference(decorView), WeakReference(window))
                    if (!delivered) {
                        config.logger.log("Session Replay bridge capture produced no frame (will retry next tick).")
                    }
                    report(delivered)
                } catch (e: Throwable) {
                    config.logger.log("Session Replay bridge capture failed: $e.")
                    report(false)
                }
            }
        } catch (e: Throwable) {
            config.logger.log("Session Replay bridge capture failed: $e.")
            return false
        }
    }

    // internal (not private) so tests can drive a snapshot pass directly.
    // Returns whether a frame was actually produced (false on every early bail),
    // so the bridge caller can tell "captured" from "silently skipped".
    internal fun generateSnapshot(
        viewRef: WeakReference<View>,
        windowRef: WeakReference<Window>,
    ): Boolean {
        if (!isActive()) return false
        val postHog = postHog ?: return false
        // Resolve expiry before taking the producer lock: this getter can notify session listeners.
        val sessionId = PostHogSessionManager.getActiveSessionId()?.toString() ?: return false
        val view = viewRef.get() ?: return false
        val window = windowRef.get() ?: return false
        val (status, generation) =
            synchronized(decorViews) {
                if (!isActive() || replaySessionId != sessionId) return false
                (decorViews[view] ?: return false) to snapshotGeneration
            }

        // Check view is still alive to avoid native crashes
        if (!view.isAlive()) return false

        val timestamp = config.dateProvider.currentTimeMillis()

        val wireframe =
            view.toScreenshotWireframe(
                window,
                status.drawState,
            ) ?: return false

        val events =
            synchronized(decorViews) {
                if (!isActive() || snapshotGeneration != generation || decorViews[view] !== status ||
                    PostHogSessionManager.peekSessionId()?.toString() != sessionId
                ) {
                    return false
                }
                snapshotEvents(view, status, wireframe, timestamp) ?: return false
            }

        // Commit above is the producer boundary. Do not invoke SDK/user callbacks under its lock.
        // A rotation after commit must not relabel this frame during core enrichment.
        captureSnapshotEvents(postHog, events, sessionId)
        return true
    }

    private fun captureSnapshotEvents(
        postHog: PostHogInterface,
        events: List<RREvent>,
        sessionId: String,
    ) {
        if (events.isNotEmpty()) {
            postHog.capture(
                PostHogEventName.SNAPSHOT.event,
                properties =
                    mapOf(
                        "\$snapshot_data" to events,
                        "\$snapshot_source" to "mobile",
                        "\$session_id" to sessionId,
                        "\$window_id" to sessionId,
                    ),
            )
        }
    }

    // Called under decorViews so a reset cannot be overwritten by an in-flight frame.
    private fun snapshotEvents(
        view: View,
        status: ViewTreeSnapshotStatus,
        wireframe: RRWireframe,
        timestamp: Long,
    ): List<RREvent>? {
        val events = mutableListOf<RREvent>()

        if (!status.sentMetaEvent) {
            val title = view.phoneWindow?.attributes?.title?.toString()?.substringAfter("/") ?: ""
            // TODO: cache and compare, if size changes, we send a ViewportResize event

            val screenSizeInfo = view.context.screenSize() ?: return null

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
            // Each window sends one screenshot node, so a frame is an update to that node.
            val lastSnapshot = status.lastSnapshot
            val incrementalMutationData =
                when {
                    lastSnapshot == null -> RRIncrementalMutationData(adds = listOf(RRMutatedNode(wireframe)))
                    lastSnapshot.id != wireframe.id ->
                        RRIncrementalMutationData(
                            adds = listOf(RRMutatedNode(wireframe)),
                            removes = listOf(RRRemovedNode(lastSnapshot.id)),
                        )
                    lastSnapshot != wireframe -> RRIncrementalMutationData(updates = listOf(RRMutatedNode(wireframe)))
                    else -> null
                }

            if (incrementalMutationData != null) {
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

        status.lastSnapshot = wireframe
        return events
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
     * its scratch objects are reused; other callers allocate per call (see the concurrent
     * capture vs delayed-screenshot-walk race test).
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
        // True for walks re-checking a capture's first walk; false re-measures text.
        val reuseTextLayouts: Boolean = false,
    ) {
        var textLayoutsCleared: Boolean = false

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
        val scratchLocation = IntArray(2)
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
        insideNoCapture: Boolean = false,
    ) {
        if (walk.shouldStop) {
            return
        }

        // Guards against a pathological hierarchy (broken custom getChildAt) recursing forever.
        if (!walk.visitedViews.add(System.identityHashCode(view))) {
            return
        }

        var walkChildren = false
        var childrenInsideNoCapture = false

        when (view.maskRule(insideNoCapture)) {
            MaskRule.COMPOSE -> {
                findMaskableComposeWidgets(view, walk)
                if (walk.shouldStop) {
                    return
                }
                // Also walk View children for interop scenarios (AndroidView, FragmentContainerView, etc.)
                walkChildren = true
            }
            // Children of an unclipped container can draw outside its rect, so they are masked too.
            MaskRule.NO_CAPTURE -> {
                view.addGlobalVisibleRect(walk)
                walkChildren = true
                childrenInsideNoCapture = true
            }
            MaskRule.MASK_VIEW -> view.addGlobalVisibleRect(walk)
            MaskRule.MASK_TEXT -> (view as TextView).addTextAreaGlobalVisibleRect(walk)
            MaskRule.CHILDREN -> walkChildren = true
            MaskRule.NONE -> Unit
        }

        if (walkChildren && view is ViewGroup && view.childCount > 0) {
            for (i in 0 until view.childCount) {
                if (walk.shouldStop) {
                    return
                }

                val viewChild = view.getChildAt(i) ?: continue

                if (!viewChild.isVisible(walk)) {
                    // A skipped-but-rendered view could be a masked widget we cannot place.
                    if (viewChild.isRenderedButUnplaceable() &&
                        (walk.failClosed || viewChild.hasMaskableContent(walk, childrenInsideNoCapture))
                    ) {
                        walk.poisoned = true
                        return
                    }
                    continue
                }

                findMaskableWidgets(viewChild, walk, childrenInsideNoCapture)
            }
        }
    }

    internal enum class MaskRule { COMPOSE, NO_CAPTURE, MASK_VIEW, MASK_TEXT, CHILDREN, NONE }

    // The one place that decides how a view is masked, shared by the mask walk and
    // hasMaskableContent so the two can't disagree.
    internal fun View.maskRule(insideNoCapture: Boolean = false): MaskRule =
        when {
            isComposeView() -> if (insideNoCapture || isNoCapture()) MaskRule.NO_CAPTURE else MaskRule.COMPOSE
            // ph-no-mask has precedence, skip masking
            isUnmasked() -> MaskRule.NONE
            insideNoCapture || isNoCapture() -> MaskRule.NO_CAPTURE
            // Only emptiness matters here; toString() would copy the text on every walk.
            this is TextView ->
                if ((text?.isNotEmpty() == true || hint?.isNotEmpty() == true) && shouldMaskTextView()) {
                    MaskRule.MASK_TEXT
                } else {
                    MaskRule.NONE
                }
            this is Spinner -> if (shouldMaskSpinner()) MaskRule.MASK_VIEW else MaskRule.NONE
            this is ImageView -> if (shouldMaskImage()) MaskRule.MASK_VIEW else MaskRule.NONE
            this is WebView -> if (isAnyInputSensitive()) MaskRule.MASK_VIEW else MaskRule.NONE
            this is ViewGroup && childCount > 0 -> MaskRule.CHILDREN
            else -> MaskRule.NONE
        }

    // Whether this subtree holds anything the walk would mask, judged without geometry, so a
    // legacy walk can tell an unplaceable sensitive view from a harmless animation.
    internal fun View.hasMaskableContent(
        walk: MaskWalk,
        insideNoCapture: Boolean = false,
    ): Boolean {
        if (!walk.visitedViews.add(System.identityHashCode(this))) {
            return false
        }
        return when (maskRule(insideNoCapture)) {
            MaskRule.COMPOSE, MaskRule.NO_CAPTURE, MaskRule.MASK_VIEW, MaskRule.MASK_TEXT -> true
            MaskRule.CHILDREN ->
                (0 until (this as ViewGroup).childCount).any { i ->
                    getChildAt(i)?.let { it.visibility == View.VISIBLE && it.hasMaskableContent(walk) } == true
                }
            MaskRule.NONE -> false
        }
    }

    // Only explicit masks count: maskAllTextInputs/maskAllImages would drop every tap on a field or image.
    private fun View.isTouchInsideExplicitMask(motionEvent: MotionEvent): Boolean =
        TouchHitTest(this, motionEvent.x, motionEvent.y).hasExplicitMaskAt(this, motionEvent.x, motionEvent.y)

    // Carries the touch down in each view's local coordinates, as touch dispatch does, so each
    // visited view costs O(1). Only a candidate mask pays for getGlobalVisibleRect, which climbs
    // to the root, so the final call matches the rect the mask walk masks.
    private inner class TouchHitTest(
        decorView: View,
        private val decorX: Float,
        private val decorY: Float,
    ) {
        private val rect = Rect()
        private val inverse = Matrix()
        private val point = FloatArray(2)

        private val windowOffset = IntArray(2).also { decorView.getLocationInWindow(it) }

        fun hasExplicitMaskAt(
            view: View,
            x: Float,
            y: Float,
            insideNoCapture: Boolean = false,
        ): Boolean {
            if (view.visibility != View.VISIBLE) {
                return false
            }
            val hit = x >= 0 && y >= 0 && x < view.width && y < view.height
            val rule = view.maskRule(insideNoCapture)
            if (hit && rule == MaskRule.NO_CAPTURE &&
                view.getGlobalVisibleRect(rect) && rect.contains(decorX.toInt(), decorY.toInt())
            ) {
                return true
            }
            if (hit && rule == MaskRule.COMPOSE &&
                hasComposeMaskAt(view)
            ) {
                return true
            }
            // Children of a container that doesn't clip them can draw, and be masked, outside it.
            val group = view as? ViewGroup ?: return false
            if (!hit && group.clipChildren) {
                return false
            }
            if (rule != MaskRule.NO_CAPTURE && rule != MaskRule.COMPOSE && rule != MaskRule.CHILDREN) {
                return false
            }
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i) ?: continue
                point[0] = x + group.scrollX - child.left
                point[1] = y + group.scrollY - child.top
                val childMatrix = child.matrix
                if (!childMatrix.isIdentity && childMatrix.invert(inverse)) {
                    inverse.mapPoints(point)
                }
                if (hasExplicitMaskAt(child, point[0], point[1], insideNoCapture = rule == MaskRule.NO_CAPTURE)) {
                    return true
                }
            }
            return false
        }

        // Only reached for a Compose view, so Compose is on the classpath. Compose bounds are
        // window coordinates, the touch is relative to the decor view.
        private fun hasComposeMaskAt(view: View): Boolean {
            val root = (view as? RootForTest)?.semanticsOwner?.unmergedRootSemanticsNode ?: return false
            return root.hasPostHogMaskAt(Offset(decorX + windowOffset[0], decorY + windowOffset[1]))
        }
    }

    // Visits every node: a child can draw outside a parent that doesn't clip, so a parent's
    // bounds can't rule its children out. postHogUnmask on a node or an ancestor wins.
    private fun SemanticsNode.hasPostHogMaskAt(
        point: Offset,
        insideMask: Boolean = false,
    ): Boolean {
        if (config.isActive(PostHogReplayUnmask)) {
            return false
        }
        val masked = insideMask || config.isActive(PostHogReplayMask)
        if (masked && boundsInWindow.contains(point)) {
            return true
        }
        return children.any { it.hasPostHogMaskAt(point, masked) }
    }

    private fun SemanticsConfiguration.isActive(key: SemanticsPropertyKey<Boolean>): Boolean = contains(key) && this[key]

    private fun View.addGlobalVisibleRect(walk: MaskWalk) {
        if (isViewStateStableForMatrixOperations()) {
            getWindowVisibleRect(walk)
            walk.addRect(walk.scratchRect)
        }
    }

    // getGlobalVisibleRect ignores the window pan applied while the soft keyboard is open
    // (adjustPan), but the captured pixels include it. The root's location in the window
    // carries that pan, so shift the rect by it to match the screenshot.
    private fun View.getWindowVisibleRect(walk: MaskWalk) {
        getGlobalVisibleRect(walk.scratchRect, walk.scratchPoint)
        rootView.getLocationInWindow(walk.scratchLocation)
        walk.scratchRect.offset(walk.scratchLocation[0], walk.scratchLocation[1])
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
        getWindowVisibleRect(walk)
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
        val abandoned = AtomicBoolean(false)
        val task =
            Runnable {
                try {
                    if (!abandoned.get()) {
                        result = block()
                    }
                } catch (e: Throwable) {
                    config.logger.log("Session Replay main-thread hop failed: $e")
                } finally {
                    latch.countDown()
                }
            }
        mainHandler.handler.post(task)
        return try {
            if (latch.await(1000, TimeUnit.MILLISECONDS)) {
                result
            } else {
                abandoned.set(true)
                mainHandler.handler.removeCallbacks(task)
                null
            }
        } catch (e: InterruptedException) {
            abandoned.set(true)
            mainHandler.handler.removeCallbacks(task)
            Thread.currentThread().interrupt()
            config.logger.log("Session Replay main-thread hop failed: $e")
            null
        } catch (e: Throwable) {
            abandoned.set(true)
            mainHandler.handler.removeCallbacks(task)
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
                    // Once per walk: a walk visits every ComposeView, all sharing this cache.
                    if (!walk.reuseTextLayouts && !walk.textLayoutsCleared) {
                        textPaintedBoundsCache.clear()
                        walk.textLayoutsCleared = true
                    }
                    // Unmerged: a merged node inherits a child's postHogUnmask, exposing its siblings.
                    val semanticsNodes = semanticsOwner.getAllSemanticsNodes(false)
                    val unmaskedLayouts = mutableSetOf<LayoutInfo>()

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
                                unmaskedLayouts.add(node.layoutInfo)
                            }

                            // postHogMask forces masking
                            isMaskEnabled -> {
                                maskableWidgets.add(node.maskBounds())
                            }

                            // no active modifier, apply default config rules
                            else -> {
                                when {
                                    (hasText || hasEditableText) &&
                                        (config.sessionReplayConfig.maskAllTextInputs || hasPassword) -> {
                                        maskableWidgets.add(node.maskBounds())
                                    }

                                    hasImage && config.sessionReplayConfig.maskAllImages -> {
                                        maskableWidgets.add(node.maskBounds())
                                    }

                                    // clearAndSetSemantics hides what the node draws, so assume it is sensitive.
                                    node.config.isClearingSemantics &&
                                        (config.sessionReplayConfig.maskAllTextInputs || config.sessionReplayConfig.maskAllImages) -> {
                                        maskableWidgets.add(node.maskBounds())
                                    }
                                }
                            }
                        }
                    }
                    if (config.sessionReplayConfig.maskAllImages) {
                        collectComposeImageRects(
                            semanticsOwner.unmergedRootSemanticsNode.layoutInfo,
                            unmaskedLayouts,
                            maskableWidgets,
                        )
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
        } else if (walk.failClosed || completed) {
            // Compose mask rects are missing or incomplete, so fail closed. Legacy walks only tolerate a timeout.
            walk.poisoned = true
        }
    }

    // Text can draw past its layout box, so cover what it paints, mapped through the node's transforms.
    private fun SemanticsNode.maskBounds(): Rect {
        val bounds = boundsInWindow.toRect()
        val painted = textPaintedBounds() ?: return bounds
        val coordinates = layoutInfo.coordinates
        if (!coordinates.isAttached) {
            return bounds
        }
        val corners =
            listOf(painted.topLeft, painted.topRight, painted.bottomLeft, painted.bottomRight)
                .map { coordinates.localToWindow(it) }
        bounds.union(
            floor(corners.minOf { it.x }).toInt(),
            floor(corners.minOf { it.y }).toInt(),
            ceil(corners.maxOf { it.x }).toInt(),
            ceil(corners.maxOf { it.y }).toInt(),
        )
        return bounds
    }

    private class CachedTextPaintedBounds(
        val text: List<AnnotatedString>?,
        val size: IntSize,
        val bounds: androidx.compose.ui.geometry.Rect?,
    )

    // clearAndSetSemantics also wipes text semantics on its own layout node, so read them from the text's
    // modifier. Throws when that can't be read, which discards the frame. Reflection: these APIs are newer
    // than the version the SDK compiles against.
    private fun SemanticsNode.textSemantics(): SemanticsConfiguration {
        if (!config.isClearingSemantics) {
            return config
        }
        for (info in layoutInfo.getModifierInfo()) {
            val coordinates = info.coordinates
            val node = coordinates.javaClass.getMethod("getTail").invoke(coordinates) ?: continue
            val applySemantics =
                try {
                    node.javaClass.getMethod("applySemantics", SemanticsPropertyReceiver::class.java)
                } catch (e: NoSuchMethodException) {
                    check(!node.isDelegatingNode()) { "Can't read the semantics delegated by $node" }
                    continue
                }
            val semantics = SemanticsConfiguration()
            applySemantics.invoke(node, semantics)
            if (semantics.contains(SemanticsProperties.Text)) {
                return semantics
            }
        }
        return config
    }

    private fun Any.isDelegatingNode(): Boolean {
        return generateSequence<Class<*>>(javaClass) { it.superclass }.any { it.name == "androidx.compose.ui.node.DelegatingNode" }
    }

    // Text(String) rebuilds its paragraph on every GetTextLayoutResult call, so a capture's walks
    // share the result until the next draw. Main thread only, like the traversal that reads it.
    private val textPaintedBoundsCache = HashMap<Int, CachedTextPaintedBounds>()

    private fun SemanticsNode.textPaintedBounds(): androidx.compose.ui.geometry.Rect? {
        val semantics = textSemantics()
        if (!semantics.contains(SemanticsProperties.Text) || semantics.contains(SemanticsProperties.EditableText)) {
            return null
        }
        val text = semantics.getOrNull(SemanticsProperties.Text)
        textPaintedBoundsCache[id]?.let {
            if (it.text == text && it.size == size) {
                return it.bounds
            }
        }
        return measureTextPaintedBounds(semantics).also {
            textPaintedBoundsCache[id] = CachedTextPaintedBounds(text, size, it)
        }
    }

    // Local bounds of what the text paints outside its layout box, or null if it stays inside.
    private fun SemanticsNode.measureTextPaintedBounds(semantics: SemanticsConfiguration): androidx.compose.ui.geometry.Rect? {
        val results = mutableListOf<TextLayoutResult>()
        if (semantics.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results) != true) {
            return null
        }
        val layout = results.firstOrNull() ?: return null
        val input = layout.layoutInput
        var glyphs = androidx.compose.ui.geometry.Rect(0f, 0f, size.width.toFloat(), size.height.toFloat())
        var overdraws = false
        if (input.overflow == TextOverflow.Visible) {
            val paragraph = layout.multiParagraph
            // This result is re-wrapped to the box, but softWrap = false draws one unwrapped line.
            val width = if (input.softWrap) paragraph.width else max(paragraph.width, paragraph.intrinsics.maxIntrinsicWidth)
            glyphs = glyphs.copy(right = max(glyphs.right, width), bottom = max(glyphs.bottom, paragraph.height))
            overdraws = true
        }
        val strokes = listOfNotNull(input.style.textStroke()) + input.text.spanStyles.mapNotNull { it.item.textStroke() }
        strokes.maxOfOrNull { it.width / 2 * max(1f, it.miter) }?.let {
            glyphs = glyphs.inflate(it)
            overdraws = true
        }
        var painted = glyphs
        val shadows = listOfNotNull(input.style.shadow) + input.text.spanStyles.mapNotNull { it.item.shadow }
        for (shadow in shadows) {
            // Skia's Gaussian blur reaches about 1.73x the radius.
            painted = painted.expandToInclude(glyphs.translate(shadow.offset).inflate(shadow.blurRadius * 2))
            overdraws = true
        }
        return if (overdraws) painted else null
    }

    // drawStyle arrived in Compose 1.4, after the version the SDK compiles against. Older runtimes can't stroke text.
    private fun Any.textStroke(): Stroke? {
        return try {
            javaClass.getMethod("getDrawStyle").invoke(this) as? Stroke
        } catch (e: NoSuchMethodException) {
            null
        }
    }

    private fun androidx.compose.ui.geometry.Rect.expandToInclude(other: androidx.compose.ui.geometry.Rect) =
        androidx.compose.ui.geometry.Rect(
            min(left, other.left),
            min(top, other.top),
            max(right, other.right),
            max(bottom, other.bottom),
        )

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
            if (current.config.isActive(key)) {
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

    internal fun RectF.setScaledScreenshotMask(
        rect: Rect,
        scaleX: Float,
        scaleY: Float,
    ) {
        set(
            floor(rect.left * scaleX),
            floor(rect.top * scaleY),
            ceil(rect.right * scaleX),
            ceil(rect.bottom * scaleY),
        )
    }

    private fun Bitmap.paintScreenshotMasks(
        rects: List<Rect>,
        sourceWidth: Int,
        sourceHeight: Int,
        canPaintMask: () -> Boolean = { true },
    ): Boolean {
        if (!isValid()) {
            this@PostHogReplayIntegration.config.logger.log("Session Replay Bitmap is invalid.")
            return false
        }
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return false
        }

        val canvas =
            try {
                Canvas(this)
            } catch (e: Throwable) {
                this@PostHogReplayIntegration.config.logger.log("Session Replay Canvas creation failed: $e.")
                return false
            }

        val scaleX = width.toFloat() / sourceWidth
        val scaleY = height.toFloat() / sourceHeight
        val maskRect = RectF()
        for (rect in rects) {
            if (!canPaintMask()) {
                return false
            }
            maskRect.setScaledScreenshotMask(rect, scaleX, scaleY)
            canvas.drawRoundRect(maskRect, 10f * scaleX, 10f * scaleY, paint)
        }
        return true
    }

    private fun View.maskVerifiedScreenshot(
        bitmap: Bitmap,
        drawState: WindowDrawState,
        armedCapture: ArmedMaskCapture,
        sourceWidth: Int,
        sourceHeight: Int,
    ): List<Rect>? {
        val postWalk =
            runOnMainThreadBlocking {
                if (width != sourceWidth || height != sourceHeight) {
                    return@runOnMainThreadBlocking null
                }
                MaskWalk(reuseTextLayouts = true).also {
                    if (!drawState.isCaptureInvalid(armedCapture.token)) {
                        findMaskableWidgets(this, it)
                    }
                }
            } ?: return null

        val captureAligned =
            drawState.finishMaskCapture(
                armedCapture.token,
                postWalk.rects,
                postWalk.poisoned,
            )
        if (width != sourceWidth || height != sourceHeight ||
            !captureAligned || !shouldKeepFrame(drawState, armedCapture.preWalk, postWalk)
        ) {
            // Masks may be out of sync with the pixels, discard to avoid a PII leak.
            config.logger.log("Session Replay screenshot discarded due to screen changes.")
            return null
        }
        return postWalk.rects.takeIf { bitmap.paintScreenshotMasks(it, sourceWidth, sourceHeight) }
    }

    private fun View.maskLegacyScreenshot(
        bitmap: Bitmap,
        drawState: WindowDrawState,
        sourceWidth: Int,
        sourceHeight: Int,
    ): Boolean = paintLegacyScreenshotMasks(bitmap, drawState, sourceWidth, sourceHeight) != null

    private fun View.paintLegacyScreenshotMasks(
        bitmap: Bitmap,
        drawState: WindowDrawState,
        sourceWidth: Int,
        sourceHeight: Int,
    ): List<Rect>? {
        val unsafeRedraw = {
            width != sourceWidth || height != sourceHeight ||
                (drawState.isOnDrawnCalled && !drawState.isOnlyAnimationRedraw)
        }
        if (unsafeRedraw()) {
            config.logger.log("Session Replay screenshot discarded due to screen changes.")
            return null
        }

        val walk =
            runOnMainThreadBlocking {
                MaskWalk(failClosed = false, shouldAbort = unsafeRedraw).also {
                    findMaskableWidgets(this, it)
                }
            } ?: return null
        if (walk.aborted || unsafeRedraw()) {
            config.logger.log("Session Replay screenshot discarded due to screen changes.")
            return null
        }
        if (walk.poisoned) {
            config.logger.log("Session Replay screenshot discarded: a view with content to mask is animating or in a transient state.")
            return null
        }

        val painted =
            bitmap.paintScreenshotMasks(walk.rects, sourceWidth, sourceHeight) {
                val safe = !unsafeRedraw()
                if (!safe) {
                    config.logger.log("Session Replay screenshot discarded due to screen changes.")
                }
                safe
            }
        return walk.rects.takeIf { painted }
    }

    private class PixelCopyRequestState {
        private var callbackFinished = false
        private var waiterAbandoned = false
        private var copySucceeded = false

        @Synchronized
        fun isAbandoned(): Boolean = waiterAbandoned

        @Synchronized
        fun complete(succeeded: Boolean): Boolean {
            if (callbackFinished) {
                return false
            }
            copySucceeded = succeeded
            callbackFinished = true
            return waiterAbandoned
        }

        @Synchronized
        fun abandon(): Boolean {
            waiterAbandoned = true
            return callbackFinished
        }

        @Synchronized
        fun succeeded(): Boolean = callbackFinished && copySucceeded
    }

    private fun finishScreenshotCapture(
        drawState: WindowDrawState,
        armedCapture: ArmedMaskCapture?,
        verifyMaskAlignment: Boolean,
    ) {
        armedCapture?.let { drawState.cancelMaskCapture(it.token) }
        if (verifyMaskAlignment) {
            drawState.reset()
        } else {
            drawState.finishLegacyCapture()
        }
    }

    private fun Window.isSecure(): Boolean = attributes?.flags?.and(FLAG_SECURE) == FLAG_SECURE

    private fun maskedScreenshotBase64(
        width: Int,
        height: Int,
        bitmapConfig: Bitmap.Config,
        compressionQuality: Int,
    ): String? {
        val lease =
            try {
                pixelCopyBitmapBuffer.acquire(width, height, bitmapConfig)
            } catch (e: Throwable) {
                config.logger.log("Session Replay screenshot setup failed: $e.")
                null
            } ?: return null
        return try {
            lease.bitmap.eraseColor(paint.color)
            lease.bitmap.webpBase64(compressionQuality)
        } catch (e: Throwable) {
            config.logger.log("Session Replay screenshot encoding failed: $e.")
            null
        } finally {
            lease.release()
        }
    }

    private fun dimScreenshotBase64(dimAmount: Float): String? {
        val bitmap =
            try {
                Bitmap.createBitmap(DIM_BITMAP_SIZE, DIM_BITMAP_SIZE, Bitmap.Config.ARGB_8888)
            } catch (e: Throwable) {
                config.logger.log("Session Replay screenshot setup failed: $e.")
                return null
            }
        return try {
            bitmap.eraseColor(Color.argb((dimAmount.coerceIn(0f, 1f) * 255).roundToInt(), 0, 0, 0))
            bitmap.webpBase64(config.sessionReplayConfig.screenshotCompressionQuality)
        } catch (e: Throwable) {
            config.logger.log("Session Replay screenshot encoding failed: $e.")
            null
        } finally {
            bitmap.recycle()
        }
    }

    private class ScreenshotGeometry(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val verifyMaskAlignment: Boolean,
        val secure: Boolean,
    )

    private fun View.toScreenshotWireframe(
        window: Window,
        drawState: WindowDrawState,
        isDialog: Boolean = false,
        bitmapConfig: Bitmap.Config =
            when (config.sessionReplayConfig.screenshotColorMode) {
                PostHogScreenshotColorMode.ARGB_8888 -> Bitmap.Config.ARGB_8888
                PostHogScreenshotColorMode.RGB_565 -> Bitmap.Config.RGB_565
            },
        windowAlpha: Float = 1f,
    ): RRWireframe? = captureScreenshot(window, drawState, isDialog, bitmapConfig, windowAlpha)?.wireframe

    // Window PixelCopy requires API 26; replay capture is gated by isSupported.
    @SuppressLint("NewApi")
    private fun View.captureScreenshot(
        window: Window,
        drawState: WindowDrawState,
        isDialog: Boolean = false,
        bitmapConfig: Bitmap.Config =
            when (config.sessionReplayConfig.screenshotColorMode) {
                PostHogScreenshotColorMode.ARGB_8888 -> Bitmap.Config.ARGB_8888
                PostHogScreenshotColorMode.RGB_565 -> Bitmap.Config.RGB_565
            },
        windowAlpha: Float = 1f,
    ): Screenshot? {
        val view = this
        val geometry =
            runOnMainThreadBlocking {
                if (!view.isVisible()) {
                    return@runOnMainThreadBlocking null
                }
                val coordinates = IntArray(2)
                if (view.isViewStateStableForMatrixOperations()) {
                    view.getLocationOnScreen(coordinates)
                }
                ScreenshotGeometry(
                    coordinates[0],
                    coordinates[1],
                    view.width,
                    view.height,
                    shouldVerifyMaskAlignment(view, drawState),
                    window.isSecure(),
                )
            } ?: return null
        val viewId = System.identityHashCode(view)
        val x = geometry.x.densityValue(screenDensity)
        val y = geometry.y.densityValue(screenDensity)
        val sourceWidth = geometry.width
        val sourceHeight = geometry.height
        val width = sourceWidth.densityValue(screenDensity)
        val height = sourceHeight.densityValue(screenDensity)
        val maskWholeDialog = isDialog && Build.VERSION.SDK_INT < Build.VERSION_CODES.P

        val screenshotScale = resolveScreenshotScale(config.sessionReplayConfig.screenshotScale, screenDensity)
        val targetWidth = scaledScreenshotDimension(sourceWidth, screenshotScale)
        val targetHeight = scaledScreenshotDimension(sourceHeight, screenshotScale)
        val copySize = twoStageCopySize(sourceWidth, sourceHeight, targetWidth, targetHeight)
        val (copyWidth, copyHeight) = copySize ?: (targetWidth to targetHeight)
        val compressionQuality = config.sessionReplayConfig.screenshotCompressionQuality

        // Recorded as one masked screenshot, as for a FLAG_SECURE window.
        fun maskedDialog(): Screenshot? =
            maskedScreenshotBase64(targetWidth, targetHeight, bitmapConfig, compressionQuality)?.let {
                Screenshot(
                    RRWireframe(
                        id = viewId,
                        x = x,
                        y = y,
                        width = width,
                        height = height,
                        type = "screenshot",
                        base64 = it,
                    ),
                    null,
                )
            }
        var base64: String? = null
        var paintedMasks: List<Rect>? = null
        var secure = geometry.secure

        // FLAG_SECURE asks that the window never be captured, so it's recorded as one mask
        // without copying its pixels.
        if (geometry.secure) {
            base64 =
                maskedScreenshotBase64(
                    targetWidth,
                    targetHeight,
                    bitmapConfig,
                    compressionQuality,
                )
            if (base64 == null) {
                recordScreenshotDiscarded(drawState)
                return null
            }
            drawState.resetScreenshotDiscards()
            return Screenshot(
                RRWireframe(
                    id = viewId,
                    x = x,
                    y = y,
                    width = width,
                    height = height,
                    type = "screenshot",
                    base64 = base64,
                ),
                null,
                secure = true,
            )
        }

        // API 26–27 copy the entire dialog surface, including its insets, into the
        // decor-sized bitmap. Only a verified empty mask walk permits copying pixels.
        val verifyMaskAlignment = maskWholeDialog || geometry.verifyMaskAlignment
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
            return if (maskWholeDialog) maskedDialog() else null
        }
        if (maskWholeDialog && armedCapture!!.preWalk.rects.isNotEmpty()) {
            finishScreenshotCapture(drawState, armedCapture, verifyMaskAlignment)
            return maskedDialog()
        }

        if (sourceWidth <= 0 || sourceHeight <= 0 || view.width != sourceWidth || view.height != sourceHeight) {
            finishScreenshotCapture(drawState, armedCapture, verifyMaskAlignment)
            recordScreenshotDiscarded(drawState)
            return null
        }
        val bitmapLease: PixelCopyBitmapBuffer.Lease
        val handler: Handler
        try {
            handler = ensurePixelCopyHandler()
            bitmapLease =
                pixelCopyBitmapBuffer.acquire(
                    copyWidth,
                    copyHeight,
                    bitmapConfig,
                ) ?: run {
                    finishScreenshotCapture(drawState, armedCapture, verifyMaskAlignment)
                    recordScreenshotDiscarded(drawState)
                    return null
                }
        } catch (e: Throwable) {
            finishScreenshotCapture(drawState, armedCapture, verifyMaskAlignment)
            config.logger.log("Session Replay screenshot setup failed: $e.")
            recordScreenshotDiscarded(drawState)
            return null
        }

        val bitmap = bitmapLease.bitmap
        val latch = CountDownLatch(1)
        val requestState = PixelCopyRequestState()

        drawState.beginPixelCopy()
        try {
            PixelCopy.request(
                window,
                bitmap,
                { copyResult ->
                    var succeeded = false
                    try {
                        if (copyResult != PixelCopy.SUCCESS) {
                            if (
                                copyResult == PixelCopy.ERROR_DESTINATION_INVALID &&
                                bitmap.config == Bitmap.Config.RGB_565 &&
                                pixelCopyBitmapBuffer.fallbackToArgb8888()
                            ) {
                                config.logger.log(
                                    "Session Replay PixelCopy does not support RGB_565; falling back to ARGB_8888.",
                                )
                            }
                            config.logger.log("Session Replay PixelCopy failed: $copyResult.")
                        } else if (!requestState.isAbandoned()) {
                            paintedMasks =
                                if (armedCapture != null) {
                                    view.maskVerifiedScreenshot(bitmap, drawState, armedCapture, sourceWidth, sourceHeight)
                                } else {
                                    view.paintLegacyScreenshotMasks(bitmap, drawState, sourceWidth, sourceHeight)
                                }
                            succeeded = paintedMasks != null
                        }
                    } catch (e: Throwable) {
                        config.logger.log("Session Replay PixelCopy failed: $e.")
                    } finally {
                        val releaseInCallback = requestState.complete(succeeded)
                        try {
                            if (releaseInCallback) {
                                bitmapLease.release()
                            }
                        } finally {
                            drawState.finishPixelCopy()
                            latch.countDown()
                        }
                    }
                },
                handler,
            )
        } catch (e: Throwable) {
            config.logger.log("Session Replay PixelCopy failed: $e.")
            try {
                if (requestState.complete(false)) {
                    bitmapLease.release()
                }
            } finally {
                drawState.finishPixelCopy()
                latch.countDown()
            }
        }

        var releaseFromWaiter = false
        val callbackFinished =
            try {
                latch.await(1000, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                config.logger.log("Session Replay PixelCopy wait interrupted: $e.")
                releaseFromWaiter = requestState.abandon()
                null
            } catch (e: Throwable) {
                config.logger.log("Session Replay PixelCopy wait failed: $e.")
                releaseFromWaiter = requestState.abandon()
                null
            }

        try {
            when (callbackFinished) {
                true -> {
                    releaseFromWaiter = true
                    if (requestState.succeeded()) {
                        try {
                            // The window can turn secure while the copy runs.
                            secure =
                                runOnMainThreadBlocking { window.isSecure() }
                                    ?: run {
                                        recordScreenshotDiscarded(drawState)
                                        return null
                                    }
                            if (secure) {
                                bitmap.eraseColor(paint.color)
                            } else if (windowAlpha != 1f) {
                                // PixelCopy precedes window opacity. Apply it only after redaction;
                                // secure windows must retain their opaque full-window mask.
                                Canvas(bitmap).drawColor(
                                    Color.argb((windowAlpha.coerceIn(0f, 1f) * 255).roundToInt(), 0, 0, 0),
                                    PorterDuff.Mode.DST_IN,
                                )
                            }
                            base64 =
                                if (copySize != null) {
                                    bitmap.downscaledWebpBase64(targetWidth, targetHeight, compressionQuality)
                                } else {
                                    bitmap.webpBase64(compressionQuality)
                                }
                        } catch (e: Throwable) {
                            config.logger.log("Session Replay screenshot encoding failed: $e.")
                        }
                    }
                }
                false -> {
                    config.logger.log("Session Replay PixelCopy timed out.")
                    releaseFromWaiter = requestState.abandon()
                }
                null -> Unit
            }
        } finally {
            finishScreenshotCapture(drawState, armedCapture, verifyMaskAlignment)
            if (releaseFromWaiter) {
                bitmapLease.release()
            }
        }

        // A discarded capture (PixelCopy failure, or a redraw race that
        // invalidates mask alignment) leaves base64 null. Emitting the
        // wireframe anyway ships an imageless "screenshot" that the player
        // renders as its placeholder tile — a visible flash. Skip the frame
        // instead; the caller retries on the next capture.
        if (base64 == null) {
            recordScreenshotDiscarded(drawState)
            return if (maskWholeDialog) maskedDialog() else null
        }
        drawState.resetScreenshotDiscards()

        return Screenshot(
            RRWireframe(
                id = viewId,
                x = x,
                y = y,
                width = width,
                height = height,
                type = "screenshot",
                base64 = base64,
            ),
            paintedMasks.takeUnless { secure },
            secure,
        )
    }

    private fun ImageView.shouldMaskImage(ancestorUnmasked: Boolean = false): Boolean {
        if (ancestorUnmasked || isUnmasked()) return false
        return isNoCapture(config.sessionReplayConfig.maskAllImages) && drawable?.shouldMaskDrawable() == true
    }

    private fun Spinner.shouldMaskSpinner(ancestorUnmasked: Boolean = false): Boolean {
        return this.isTextInputSensitive(ancestorUnmasked)
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

    @SuppressLint("AnnotateVersionCheck")
    private fun isSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    }

    override fun start(resumeCurrent: Boolean) {
        // Remember an explicit start asked for while automatic replay is off. The event gate can
        // defer it, so the intent must be recorded before checking that gate.
        if (!config.sessionReplay) {
            startedWithAutomaticDisabled = true
        }

        startRecording(resumeCurrent)
    }

    // config.sessionReplay controls automatic starts; a recording the app started explicitly
    // while it was off must survive them.
    private fun isAutomaticStartPermitted(): Boolean = config.sessionReplay || startedWithAutomaticDisabled

    private fun startRecording(resumeCurrent: Boolean) {
        // Event triggers may change while an automatic start is queued on main.
        if (shouldWaitForEventTriggers()) {
            val triggers = config.remoteConfigHolder?.getEventTriggers()
            config.logger.log(
                "[Session Replay] Event triggers configured. Integration will not start until any of these events are captured: $triggers",
            )
            return
        }

        val currentSessionId = postHog?.getSessionId()?.toString()

        // Keep producer commits and the bitmap buffer lifecycle on the same recording run.
        val claimed =
            synchronized(pixelCopyBitmapBuffer) {
                synchronized(decorViews) {
                    // Re-check under the lock: callers gate outside it, and stop() can clear the
                    // manual-start marker in between, which would otherwise leave this start live.
                    // A start queued on main before uninstall (e.g. optOut) must not run after it.
                    if (!isAutomaticStartPermitted() || postHog == null) {
                        false
                    } else {
                        resetSessionStateIfNeeded(currentSessionId, force = !resumeCurrent)
                        pixelCopyBitmapBuffer.open()
                        isSessionReplayActive = true
                        true
                    }
                }
            }
        if (!claimed) return

        if (isNativeSdk) {
            mainHandler.handler.post { requestSceneCapture() }
        }

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
            snapshotGeneration++
            sceneImages.clear()
            sceneDirtyViews.clear()
            sceneIds.clear()
            nextSceneId = FIRST_SCENE_IMAGE_ID
            lastScene = null
            sceneViewport = null
            decorViews.entries.forEach {
                resetViewSnapshotStates(it.value)
            }
        }
    }

    override fun stop() {
        stopRecording(resetManualStart = true)
    }

    private fun stopRecording(resetManualStart: Boolean = false) {
        synchronized(pixelCopyBitmapBuffer) {
            synchronized(decorViews) {
                if (resetManualStart) {
                    startedWithAutomaticDisabled = false
                }
                isSessionReplayActive = false
                snapshotGeneration++
                pixelCopyBitmapBuffer.close()
                sceneImages.clear()
                sceneDirtyViews.clear()
                lastScene = null
                sceneViewport = null
                decorViews.values.forEach { it.drawState.invalidateMaskCapture() }
            }
        }
    }

    override fun isActive(): Boolean {
        return isSessionReplayActive
    }

    override fun debugProperties(): Map<String, Any> {
        val props = mutableMapOf<String, Any>()

        val bufferingSnapshot = bufferingSnapshot()
        val active = bufferingSnapshot.active
        val buffering = active && bufferingSnapshot.buffering
        props["\$recording_status"] =
            when {
                !active -> "disabled"
                buffering -> "buffering"
                else -> "active"
            }

        if (buffering) {
            props["\$sdk_debug_replay_flush_hold_reason"] =
                if (bufferingSnapshot.awaitingRemoteConfig) "awaiting_remote_config" else "below_minimum_duration"
        }

        // Read-only: getSessionId() can rotate an idle session and fire onSessionIdChanged mid-snapshot.
        val eventTriggerState = eventTriggerState(PostHogSessionManager.peekSessionId()?.toString())
        val eventTriggerStatus =
            triggerStatus(
                // postHog is null when install() short-circuited (unsupported API) or after uninstall();
                // a trigger can never fire there, so report it disabled rather than pending.
                configured = postHog != null && eventTriggerState.configured,
                activated = eventTriggerState.activated,
            )
        props["\$sdk_debug_replay_event_trigger_status"] = eventTriggerStatus

        val linkedFlagSnapshot = config.remoteConfigHolder?.sessionReplayLinkedFlagSnapshot()
        val linkedFlagStatus =
            triggerStatus(
                configured = postHog != null && linkedFlagSnapshot?.configured == true,
                activated = linkedFlagSnapshot?.activated == true,
            )
        props["\$sdk_debug_replay_linked_flag_trigger_status"] = linkedFlagStatus

        val pendingConditions =
            listOfNotNull(
                "event_trigger".takeIf { eventTriggerStatus == "trigger_pending" },
                "linked_flag".takeIf { linkedFlagStatus == "trigger_pending" },
            )
        if (pendingConditions.isNotEmpty()) {
            props["\$sdk_debug_replay_pending_trigger_conditions"] = pendingConditions
        }

        props["\$sdk_debug_replay_capture_mode"] = "screenshot"
        props["\$sdk_debug_replay_internal_buffer_length"] =
            if (buffering) replayQueue?.bufferDepth ?: 0 else replayQueue?.size ?: 0

        return props
    }

    private fun triggerStatus(
        configured: Boolean,
        activated: Boolean,
    ): String =
        when {
            !configured -> "trigger_disabled"
            activated -> "trigger_activated"
            else -> "trigger_pending"
        }

    /**
     * Called when an event is captured. Checks if the event matches any configured triggers
     * and starts session recording if so, provided the other gates permit the session.
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
            // A matched trigger only lifts the event-trigger gate. The master switch, the project
            // flag and the sampling decision still decide, as on every other automatic start path.
            if (!isRecordingPermittedForCurrentSession()) {
                config.logger.log(
                    "[Session Replay] Event trigger matched: $event, but recording is not permitted for session $currentSessionId.",
                )
                return
            }
            config.logger.log("[Session Replay] Event trigger matched: $event. Starting replay for session $currentSessionId.")
            // Do not call start(): only an explicit request may establish manual-start provenance.
            startRecording(resumeCurrent = true)
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

        // Invalidate immediately; reinitialization may be queued behind UI work.
        if (replaySessionId != currentSessionId) stopRecording()
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

        // The listener can fire from any thread that calls capture(); automatic restarts
        // are scheduled on main, after the synchronous producer invalidation above.
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
            if (!isAutomaticStartPermitted()) {
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
            // Do not call start(): session rotation is an automatic transition.
            startRecording(resumeCurrent = false)
        }
    }

    /**
     * Returns true if event triggers are configured and the current session has not been activated yet.
     */
    private fun shouldWaitForEventTriggers(): Boolean {
        val postHog = this.postHog ?: return false
        val currentSessionId = postHog.getSessionId()?.toString() ?: return false
        val state = eventTriggerState(currentSessionId)
        return state.configured && !state.activated
    }

    private data class EventTriggerState(val configured: Boolean, val activated: Boolean)

    private fun eventTriggerState(sessionId: String?): EventTriggerState {
        val configured = !config.remoteConfigHolder?.getEventTriggers().isNullOrEmpty()
        val activatedSession = synchronized(eventTriggersLock) { triggerActivatedSessionId }
        return EventTriggerState(
            configured = configured,
            activated = sessionId != null && activatedSession == sessionId,
        )
    }

    private fun resetSessionStateIfNeeded(
        currentSessionId: String?,
        force: Boolean = false,
    ) {
        if (!force && replaySessionId == currentSessionId) {
            return
        }

        synchronized(decorViews) {
            replaySessionId = currentSessionId
            clearSnapshotStates()
        }
        resetBufferingState()
    }

    // MARK: - PostHogReplayBufferDelegate

    private val isBuffering: Boolean
        get() = bufferingSnapshot().buffering

    // Single lock acquisition for the active flag, the buffering decision and its reason, so
    // debugProperties() never pairs a status with a hold reason from a different snapshot.
    private fun bufferingSnapshot(): BufferingSnapshot =
        synchronized(bufferingLock) {
            val active = isSessionReplayActive
            if (awaitingFirstRemoteConfig) {
                return BufferingSnapshot(active = active, buffering = true, awaitingRemoteConfig = true)
            }
            val minimumDuration = cachedMinimumDurationMs
            val buffering = minimumDuration != null && minimumDuration > 0 && !hasPassedMinimumDuration
            BufferingSnapshot(active = active, buffering = buffering, awaitingRemoteConfig = false)
        }

    private data class BufferingSnapshot(val active: Boolean, val buffering: Boolean, val awaitingRemoteConfig: Boolean)

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
        if (!isAutomaticStartPermitted() || !remoteConfig.isSessionReplayFlagActive()) {
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

        if (!isAutomaticStartPermitted() || !remoteConfig.isSessionReplayFlagActive()) {
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
                // Re-check at the transition so a queued automatic resume cannot use stale gates.
                if (!isSessionReplayActive && isRecordingPermittedForCurrentSession()) {
                    // Force a fresh keyframe for the resumed segment. While stopped, per-view snapshot
                    // state is frozen and can reference a full snapshot that was never delivered (e.g. a
                    // first-config-off opening window that was dropped), so resuming against it would emit
                    // orphaned incremental snapshots the player can't anchor. Clear the state and force a
                    // redraw so the resumed segment starts with meta + full snapshot — without rotating the
                    // session or touching the cold-start buffering state (unlike startRecording(false)).
                    clearSnapshotStates()
                    startRecording(resumeCurrent = true)
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
            // Invalidate both the producer lease and queue persistence synchronously.
            // Posting the stop would leave in-flight frames eligible until main runs it.
            stopRecording()
        }
    }

    // Whether to buffer until the live remote config resolves: only when a remote config holder
    // exists and hasn't resolved yet. PostHog.setup always requests /config, so the
    // onRemoteConfig callback disarms the gate.
    private fun shouldAwaitFirstRemoteConfig(): Boolean {
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
        private const val DIM_LAYER_ID: Int = 10_000_000
        private const val DIM_BITMAP_SIZE: Int = 8
        private const val FIRST_SCENE_IMAGE_ID: Int = 10_000_001

        const val PH_NO_CAPTURE_LABEL: String = "ph-no-capture"
        const val PH_NO_MASK_LABEL: String = "ph-no-mask"

        // Retained for JVM binary compatibility.
        const val ANDROID_COMPOSE_VIEW_CLASS_NAME: String = com.posthog.android.internal.ANDROID_COMPOSE_VIEW_CLASS_NAME
        const val ANDROID_COMPOSE_VIEW: String = "AndroidComposeView"

        // Pre-walk re-arm attempts per capture: a screen that redraws during every attempt
        // discards this tick and retries at the next scheduled snapshot.
        private const val MAX_BASELINE_ARM_ATTEMPTS: Int = 3

        private val integrationInstalled = AtomicBoolean(false)
    }
}

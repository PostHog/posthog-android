package com.posthog.android.sample

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WindowCompositionProofActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var overlay: Dialog
    private var nestedOverlay: Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getStringExtra("scene") == "bottom-sheet") {
            CheckoutProof(this).show()
            return
        }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(20, 70, 180)) }
        root.addView(
            TextView(this).apply {
                text = "PROOF PRIVATE ACTIVITY"
                tag = "ph-no-capture"
                textSize = 21f
                setTextColor(Color.YELLOW)
                setBackgroundColor(Color.rgb(120, 30, 100))
            },
            FrameLayout.LayoutParams(450, 100).apply {
                leftMargin = 60
                topMargin = 340
            },
        )
        setContentView(root)
        val scene = intent.getStringExtra("scene") ?: "transparent"
        if (scene == "memory-single") return
        val wrap = scene in setOf("wrap", "nested", "memory-nested", "visibility", "nonfocus", "windowalpha")
        overlay =
            Dialog(this).apply {
                window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                val content = FrameLayout(this@WindowCompositionProofActivity)
                val card = FrameLayout(this@WindowCompositionProofActivity)
                card.addView(
                    object : View(this@WindowCompositionProofActivity) {
                        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

                        override fun onDraw(canvas: Canvas) {
                            paint.color = Color.argb(128, 255, 0, 0)
                            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
                            paint.color = Color.WHITE
                            canvas.drawCircle(width / 2f, height / 2f, width / 4f, paint)
                        }
                    },
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                )
                card.addView(
                    TextView(this@WindowCompositionProofActivity).apply {
                        text = "PROOF PRIVATE DIALOG"
                        tag = "ph-no-capture"
                        textSize = 15f
                        setTextColor(Color.YELLOW)
                        setBackgroundColor(Color.rgb(40, 120, 40))
                    },
                    FrameLayout.LayoutParams(320, 70, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL),
                )
                content.addView(card, FrameLayout.LayoutParams(360, 360, Gravity.CENTER))
                setContentView(content)
                window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                if (scene == "nonfocus") window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                window?.setDimAmount(0.35f)
                window?.setLayout(
                    if (wrap) WindowManager.LayoutParams.WRAP_CONTENT else WindowManager.LayoutParams.MATCH_PARENT,
                    if (wrap) WindowManager.LayoutParams.WRAP_CONTENT else WindowManager.LayoutParams.MATCH_PARENT,
                )
                show()
                if (scene == "windowalpha") {
                    window?.let { it.attributes = it.attributes.apply { alpha = 0.5f } }
                }
                window?.setLayout(
                    if (wrap) WindowManager.LayoutParams.WRAP_CONTENT else WindowManager.LayoutParams.MATCH_PARENT,
                    if (wrap) WindowManager.LayoutParams.WRAP_CONTENT else WindowManager.LayoutParams.MATCH_PARENT,
                )
            }
        if (scene == "nested" || scene == "memory-nested") {
            nestedOverlay =
                Dialog(this).apply {
                    window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    setContentView(View(this@WindowCompositionProofActivity).apply { setBackgroundColor(Color.GREEN) })
                    window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    window?.setDimAmount(0.60f)
                    show()
                    window?.setLayout(280, 280)
                }
        }
        if (scene != "memory-nested") mainHandler.postDelayed({ capture(scene) }, 1200)
        if (scene == "visibility") {
            mainHandler.postDelayed({
                overlay.hide()
                logVisibility("hidden")
            }, 5000)
            mainHandler.postDelayed({
                overlay.show()
                logVisibility("shown")
            }, 8000)
            mainHandler.postDelayed({
                overlay.dismiss()
                logVisibility("dismissed")
            }, 11000)
        }
    }

    private fun logVisibility(state: String) {
        val decorView = overlay.window?.decorView
        Log.i(
            TAG,
            "visibility $state attached=${decorView?.isAttachedToWindow} shown=${decorView?.isShown} visibility=${decorView?.visibility}",
        )
    }

    private fun capture(scene: String) {
        val activityWindow = window
        val dialogWindow = overlay.window ?: return
        val activityView = activityWindow.decorView
        val dialogView = dialogWindow.decorView
        val activityLocation = IntArray(2).also(activityView::getLocationOnScreen)
        val dialogLocation = IntArray(2).also(dialogView::getLocationOnScreen)
        val dialogWindowLocation = IntArray(2).also(dialogView::getLocationInWindow)
        val dialogSurfaceLocation = IntArray(2).also(dialogView::getLocationInSurface)
        val nestedView = nestedOverlay?.window?.decorView
        val nestedLocation = nestedView?.let { IntArray(2).also(it::getLocationOnScreen).contentToString() }
        Log.i(
            TAG,
            "scene=$scene windowClass=${overlay.javaClass.name} " +
                "activity=${activityView.width}x${activityView.height}@${activityLocation.contentToString()} " +
                "dialog=${dialogView.width}x${dialogView.height}@${dialogLocation.contentToString()} " +
                "inWindow=${dialogWindowLocation.contentToString()} inSurface=${dialogSurfaceLocation.contentToString()} " +
                "dim=${dialogWindow.attributes.dimAmount} dialogFocus=${dialogView.hasWindowFocus()} " +
                "opacity=${dialogWindow.attributes.alpha} nested=${nestedView?.width}x${nestedView?.height}@$nestedLocation " +
                "nestedDim=${nestedOverlay?.window?.attributes?.dimAmount}",
        )
        Thread {
            val handlerThread = HandlerThread("proof-pixel-copy").apply { start() }
            try {
                copy(scene, "activity", activityWindow, activityView.width, activityView.height, Bitmap.Config.ARGB_8888, handlerThread)
                copy(scene, "dialog-argb", dialogWindow, dialogView.width, dialogView.height, Bitmap.Config.ARGB_8888, handlerThread)
                copy(
                    scene,
                    "dialog-decor-crop",
                    dialogWindow,
                    dialogView.width,
                    dialogView.height,
                    Bitmap.Config.ARGB_8888,
                    handlerThread,
                    Rect(0, 0, dialogView.width, dialogView.height),
                )
                if (Build.VERSION.SDK_INT == 26 && scene == "wrap") {
                    copy(
                        scene,
                        "dialog-surface-crop",
                        dialogWindow,
                        dialogView.width,
                        dialogView.height,
                        Bitmap.Config.ARGB_8888,
                        handlerThread,
                        Rect(
                            dialogSurfaceLocation[0],
                            dialogSurfaceLocation[1],
                            dialogView.width + dialogSurfaceLocation[0],
                            dialogView.height + dialogSurfaceLocation[1],
                        ),
                    )
                }
                copy(scene, "dialog-rgb565", dialogWindow, dialogView.width, dialogView.height, Bitmap.Config.RGB_565, handlerThread)
                val width = dialogView.width
                val height = dialogView.height
                if (width > 400 && height > 400) {
                    val crop = Rect(width / 2 - 200, height / 2 - 200, width / 2 + 200, height / 2 + 200)
                    copy(scene, "dialog-crop", dialogWindow, 200, 200, Bitmap.Config.ARGB_8888, handlerThread, crop)
                }
                copyAcrossFormats(scene, dialogWindow, width, height, activityView.width, activityView.height, handlerThread)
                nestedOverlay?.window?.let { nestedWindow ->
                    nestedView?.let {
                        copy(scene, "nested-argb", nestedWindow, it.width, it.height, Bitmap.Config.ARGB_8888, handlerThread)
                    }
                }
            } finally {
                handlerThread.quitSafely()
            }
        }.start()
    }

    private fun copy(
        scene: String,
        name: String,
        source: Window,
        width: Int,
        height: Int,
        config: Bitmap.Config,
        handlerThread: HandlerThread,
        crop: Rect? = null,
    ) {
        if (width <= 0 || height <= 0) return
        val bitmap = Bitmap.createBitmap(width, height, config)
        bitmap.eraseColor(Color.MAGENTA)
        val latch = CountDownLatch(1)
        var result = -1
        val handler = Handler(handlerThread.looper)
        PixelCopy.request(source, crop, bitmap, {
            result = it
            latch.countDown()
        }, handler)
        val finished = latch.await(5, TimeUnit.SECONDS)
        if (!finished || result != PixelCopy.SUCCESS) {
            Log.i(TAG, "$scene/$name PixelCopy finished=$finished result=$result")
            bitmap.recycle()
            return
        }
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val transparent = pixels.count { Color.alpha(it) == 0 }
        val partial = pixels.count { Color.alpha(it) in 1..254 }
        val opaque = pixels.size - transparent - partial
        val encoded = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.WEBP, 80, it) }.toByteArray()
        val decoded = android.graphics.BitmapFactory.decodeByteArray(encoded, 0, encoded.size)
        val center = decoded.getPixel(width / 2, height / 2)
        val corner = decoded.getPixel(0, 0)
        val output = File(getExternalFilesDir(null), "$scene-$name.webp")
        output.writeBytes(encoded)
        Log.i(
            TAG,
            "$scene/$name config=$config allocation=${bitmap.allocationByteCount} " +
                "alpha=[$transparent,$partial,$opaque] decodedCenter=${Integer.toHexString(center)} " +
                "decodedCorner=${Integer.toHexString(corner)} webp=${encoded.size} output=$output",
        )
        decoded.recycle()
        bitmap.recycle()
    }

    private fun copyAcrossFormats(
        scene: String,
        source: Window,
        width: Int,
        height: Int,
        maximumWidth: Int,
        maximumHeight: Int,
        handlerThread: HandlerThread,
    ) {
        val scratch = Bitmap.createBitmap(maximumWidth, maximumHeight, Bitmap.Config.ARGB_8888)
        val allocation = scratch.allocationByteCount
        try {
            for (config in listOf(Bitmap.Config.RGB_565, Bitmap.Config.ARGB_8888)) {
                scratch.reconfigure(width, height, config)
                val beforeAlpha = scratch.hasAlpha()
                val latch = CountDownLatch(1)
                var result = -1
                PixelCopy.request(source, scratch, {
                    result = it
                    latch.countDown()
                }, Handler(handlerThread.looper))
                val finished = latch.await(5, TimeUnit.SECONDS)
                val pixels = IntArray(width * height)
                if (finished && result == PixelCopy.SUCCESS) {
                    scratch.getPixels(pixels, 0, width, 0, 0, width, height)
                }
                val transparent = pixels.count { Color.alpha(it) == 0 }
                val partial = pixels.count { Color.alpha(it) in 1..254 }
                Log.i(
                    TAG,
                    "$scene/reconfigured $config finished=$finished result=$result " +
                        "beforeAlpha=$beforeAlpha afterAlpha=${scratch.hasAlpha()} " +
                        "allocation=${scratch.allocationByteCount} initialAllocation=$allocation " +
                        "transparent=$transparent partial=$partial corner=${Integer.toHexString(scratch.getPixel(0, 0))}",
                )
            }
        } finally {
            scratch.recycle()
        }
    }

    companion object {
        private const val TAG = "WindowCompositionProof"
    }
}

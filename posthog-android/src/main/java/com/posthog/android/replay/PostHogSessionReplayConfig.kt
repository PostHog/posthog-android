package com.posthog.android.replay

import com.posthog.PostHogExperimental

/**
 * Android session replay capture options.
 *
 * Use this as `PostHogAndroidConfig.sessionReplayConfig` when enabling session replay through
 * `PostHogConfig.sessionReplay`.
 */
public class PostHogSessionReplayConfig
    @JvmOverloads
    constructor(
        /**
         * Enable masking of all text and text input fields.
         * The mask applies to wireframe capture and to screenshot capture.
         * Defaults to true.
         */
        public var maskAllTextInputs: Boolean = true,
        /**
         * Enable masking of all images to a placeholder.
         * The mask applies to wireframe capture and to screenshot capture.
         * Defaults to true.
         */
        public var maskAllImages: Boolean = true,
        /**
         * Enable capturing of logcat as console events
         * Defaults to true
         */
        public var captureLogcat: Boolean = true,
        /**
         * Converts custom Drawable to Bitmap
         * By default PostHog tries to convert the Drawable to Bitmap, the supported types are
         * BitmapDrawable, ColorDrawable, GradientDrawable, InsetDrawable, LayerDrawable, RippleDrawable
         */
        public var drawableConverter: PostHogDrawableConverter? = null,
        /**
         * Capture each frame as a masked screenshot instead of a wireframe.
         * Defaults to false, which captures the views on the screen as a wireframe.
         * A wireframe only covers classic Android View types, so a screen that Jetpack Compose
         * draws records as a blank screen. Set this option to true for Jetpack Compose apps.
         * The mask options still apply to a screenshot, but a screenshot can show sensitive
         * information that the mask options do not cover. Use with caution.
         */
        public var screenshot: Boolean = false,
        /**
         * Debouncer delay used to reduce the number of snapshots captured and reduce performance impact.
         * This is used for capturing the view as a wireframe or screenshot.
         * The lower the number, the more snapshots are captured and the higher the performance impact.
         * Defaults to 1000ms = 1s.
         * Ps: it was 500ms by default until version 3.8.2.
         */
        @Deprecated("Use throttleDelayMs instead")
        public var debouncerDelayMs: Long = 1000,
        /**
         * Throttling delay used to reduce the number of snapshots captured and reduce performance impact.
         * This is used for capturing the view as a wireframe or screenshot.
         * The lower the number, the more snapshots are captured and the higher the performance impact.
         * Defaults to 1000ms = 1s.
         */
        public var throttleDelayMs: Long = 1000,
        /**
         * Local sample rate for session recording, a value between 0.0 and 1.0.
         * When set, this takes precedence over the remote config sample rate.
         * `null` means no local override, so the SDK uses the remote config value.
         * Defaults to `null`.
         */
        public var sampleRate: Double? = null,
    ) {
        /**
         * Verifies mask alignment for session replay screenshots.
         * This can preserve screenshots during pixel-only redraws, including continuously animated
         * content, but performs additional view hierarchy walks while a screenshot is captured.
         * Defaults to false. Windows with a Compose root always use the verified path regardless
         * of this flag, so setting it to false does not disable verification for them.
         */
        @PostHogExperimental
        public var verifyScreenshotMaskAlignment: Boolean = false

        /**
         * WebP compression quality for screenshots, clamped to 0..100. Defaults to 30.
         * Higher values generally retain more detail and produce larger payloads.
         * Compression is lossy, except on Android 10 (API 29), where the platform's legacy
         * WebP encoder uses lossless compression at quality 100.
         * Does not change screenshot resolution or enable screenshot capture.
         */
        @PostHogExperimental
        @Volatile
        public var screenshotCompressionQuality: Int = 30
            set(value) {
                field = value.coerceIn(0, 100)
            }

        /**
         * Multiplier for the physical width and height of screenshot captures, clamped to 0.1..1.0.
         * For example, 0.5 captures half the width and height, or one quarter of the pixels.
         * Dimensions are rounded up to at least one pixel; the logical replay viewport is unchanged.
         * Defaults to 1.0 (full resolution). NaN and infinite values reset the scale to 1.0.
         * Does not change compression quality, color mode, or enable screenshot capture.
         */
        @PostHogExperimental
        @Volatile
        public var screenshotScale: Float = 1f
            set(value) {
                field = if (value.isFinite()) value.coerceIn(0.1f, 1f) else 1f
            }

        /**
         * Pixel format used for screenshot captures. Defaults to [PostHogScreenshotColorMode.ARGB_8888]
         * to preserve alpha and color precision before lossy WebP compression.
         * [PostHogScreenshotColorMode.RGB_565] uses less bitmap memory but reduces color precision
         * and removes alpha, making transparent window regions appear black. Devices that reject
         * RGB_565 fall back to ARGB_8888. Does not enable screenshot capture.
         */
        @PostHogExperimental
        @Volatile
        public var screenshotColorMode: PostHogScreenshotColorMode = PostHogScreenshotColorMode.ARGB_8888

        init {
            // for keeping back compatibility
            @Suppress("DEPRECATION")
            if (debouncerDelayMs != 1000L) {
                throttleDelayMs = debouncerDelayMs
            }
        }
    }

package com.posthog.android.replay

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
         * Enable masking of all text and text input fields
         * Defaults to true
         */
        public var maskAllTextInputs: Boolean = true,
        /**
         * Enable masking of all images to a placeholder
         * Defaults to true
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
         * By default Session replay will capture all the views on the screen as a wireframe,
         * By enabling this option, PostHog will capture the screenshot of the screen.
         * The screenshot may contain sensitive information, use with caution.
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
        init {
            // for keeping back compatibility
            @Suppress("DEPRECATION")
            if (debouncerDelayMs != 1000L) {
                throttleDelayMs = debouncerDelayMs
            }
        }
    }

package com.posthog.android.replay

import com.posthog.PostHogExperimental

@PostHogExperimental
public class PostHogSessionReplayConfig
@JvmOverloads
constructor(
    /**
     * Enable masking of all text input fields
     * Defaults to true
     * This isn't supported if using Jetpack Compose views, use with caution
     */
    @PostHogExperimental
    public var maskAllTextInputs: Boolean = true,
    /**
     * Enable masking of all images to a placeholder
     * Defaults to true
     * This isn't supported if using Jetpack Compose views, use with caution
     */
    @PostHogExperimental
    public var maskAllImages: Boolean = true,
    /**
     * Enable capturing of logcat as console events
     * Defaults to true
     */
    @PostHogExperimental
    public var captureLogcat: Boolean = true,
    /**
     * Converts custom Drawable to Bitmap
     * By default PostHog tries to convert the Drawable to Bitmap, the supported types are
     * BitmapDrawable, ColorDrawable, GradientDrawable, InsetDrawable, LayerDrawable, RippleDrawable
     */
    @PostHogExperimental
    public var drawableConverter: PostHogDrawableConverter? = null,
    /**
     * By default Session replay will capture all the views on the screen as a wireframe,
     * By enabling this option, PostHog will capture the screenshot of the screen.
     * The screenshot may contain sensitive information, use with caution.
     */
    @PostHogExperimental
    public var screenshot: Boolean = false,
    /**
     * Deboucer delay used to reduce the number of snapshots captured and reduce performance impact
     * This is used for capturing the view as a wireframe or screenshot
     * The lower the number more snapshots will be captured but higher the performance impact
     * Defaults to 500ms
     */
    @PostHogExperimental
    public var debouncerDelayMs: Long = 500,
    /**
     * Define the minimum duration for sessions to be recorded.
     * This is useful if you want to exclude sessions that are too short to be useful.
     * Defaults to 1000ms
     */
    @PostHogExperimental
    public var minSessionDurationMs: Long = 1000,
)

package com.posthog.android.replay

import com.posthog.PostHogExperimental

@PostHogExperimental
public class PostHogSessionReplayConfig(
    /**
     * Enable masking of all text input fields
     * Defaults to true
     */
    @PostHogExperimental
    public var maskAllTextInputs: Boolean = true,

    /**
     * Enable masking of all images to a placeholder
     * Defaults to true
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
)

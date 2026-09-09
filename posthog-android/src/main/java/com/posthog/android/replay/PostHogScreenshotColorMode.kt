package com.posthog.android.replay

import com.posthog.PostHogExperimental

/** Pixel format for session replay screenshot captures, independent of resolution and compression. */
@PostHogExperimental
public enum class PostHogScreenshotColorMode {
    /** Four bytes per pixel, preserving alpha and eight bits per color channel before encoding. */
    ARGB_8888,

    /** Two bytes per pixel, with reduced color precision and no alpha channel. */
    RGB_565,
}

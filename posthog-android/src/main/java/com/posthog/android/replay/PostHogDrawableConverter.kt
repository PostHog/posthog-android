package com.posthog.android.replay

import android.graphics.Bitmap
import android.graphics.drawable.Drawable

/**
 * Converts Android [Drawable] instances that the SDK cannot render by default into [Bitmap]s for
 * session replay screenshots or wireframes.
 */
public fun interface PostHogDrawableConverter {
    /**
     * Converts [drawable] to a bitmap representation.
     *
     * @param drawable Drawable to convert.
     * @return A bitmap to capture, or `null` when this converter cannot handle the drawable.
     */
    public fun convert(drawable: Drawable): Bitmap?
}

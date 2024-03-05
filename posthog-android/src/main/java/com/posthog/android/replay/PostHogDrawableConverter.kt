package com.posthog.android.replay

import android.graphics.Bitmap
import android.graphics.drawable.Drawable

public fun interface PostHogDrawableConverter {
    public fun convert(drawable: Drawable): Bitmap?
}

package com.posthog.android.internal

import android.content.Context
import com.posthog.PostHogContext

// PostHogContext

internal class PostHogAndroidContext(context: Context) : PostHogContext {
    override fun getStaticContext(): Map<String, Any>? {
        return null
    }

    override fun getDynamicContext(): Map<String, Any>? {
        return null
    }
}

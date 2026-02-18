package com.posthog.internal

import com.posthog.PostHogInternal

/**
 * Callback invoked after remote config finishes loading.
 * Use this to notify listeners that remote config values may have changed.
 */
@PostHogInternal
public fun interface PostHogOnRemoteConfigLoaded {
    public fun loaded()
}

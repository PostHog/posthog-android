package com.posthog.internal

import com.posthog.PostHogInternal

/**
 * Callback invoked after a remote config resolution attempt finishes — whether it succeeded or
 * terminally failed (offline/error). Listeners distinguish the two via
 * [PostHogRemoteConfig.hasRemoteConfigFetched]: true means fresh values were applied, false means
 * no live config could be resolved and the cached values still stand.
 */
@PostHogInternal
public fun interface PostHogOnRemoteConfigLoaded {
    public fun loaded()
}

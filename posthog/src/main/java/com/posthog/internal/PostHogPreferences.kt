package com.posthog.internal

import com.posthog.PostHogInternal

/**
 * Interface for caching context either in memory or in the disk depending on the
 * concrete implementation
 */
@PostHogInternal
public interface PostHogPreferences {
    public fun getValue(key: String, defaultValue: Any? = null): Any?

    public fun setValue(key: String, value: Any)

    public fun clear(except: List<String> = emptyList())

    public fun remove(key: String)

    public fun getAll(): Map<String, Any>
}

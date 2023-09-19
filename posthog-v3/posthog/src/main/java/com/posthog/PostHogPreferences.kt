package com.posthog

// Properties
public interface PostHogPreferences {
    public fun getValue(key: String, defaultValue: Any? = null): Any?

    public fun setValue(key: String, value: Any)

    public fun clear()
}

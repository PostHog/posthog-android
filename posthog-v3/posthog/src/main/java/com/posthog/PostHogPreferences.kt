package com.posthog

public interface PostHogPreferences {
    public fun getValue(key: String, defaultValue: Any? = null): Any?

    public fun setValue(key: String, value: Any)

    public fun clear(except: List<String>)

    public fun remove(key: String)
}

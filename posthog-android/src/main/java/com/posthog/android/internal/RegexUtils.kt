package com.posthog.android.internal

internal fun isMatchingRegex(
    value: String,
    pattern: String,
): Boolean {
    return try {
        Regex(pattern).containsMatchIn(value)
    } catch (e: Throwable) {
        false
    }
}

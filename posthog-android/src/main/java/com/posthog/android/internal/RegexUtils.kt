package com.posthog.android.internal

internal fun isValidRegex(str: String): Boolean {
    return try {
        Regex(str)
        true
    } catch (e: Exception) {
        false
    }
}

internal fun isMatchingRegex(
    value: String,
    pattern: String,
): Boolean {
    return if (!isValidRegex(pattern)) {
        false
    } else {
        try {
            Regex(pattern).containsMatchIn(value)
        } catch (e: Exception) {
            false
        }
    }
}

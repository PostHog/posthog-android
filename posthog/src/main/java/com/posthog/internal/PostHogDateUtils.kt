package com.posthog.internal

import com.google.gson.internal.bind.util.ISO8601Utils
import com.posthog.PostHogInternal
import java.text.ParsePosition
import java.util.Date

@PostHogInternal
public fun parseISO8601Date(dateString: String): Date? {
    return try {
        ISO8601Utils.parse(dateString, ParsePosition(0))
    } catch (e: Throwable) {
        null
    }
}

@PostHogInternal
public fun formatISO8601Date(date: Date): String {
    return ISO8601Utils.format(date, true)
}

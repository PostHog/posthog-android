package com.posthog.internal

import com.google.gson.Gson
import com.posthog.PostHogInternal

/**
 * Provides a shared plain Gson instance for general-purpose JSON serialization.
 *
 * This is intentionally a plain Gson without custom type adapters, suitable for
 * serializing/deserializing user-provided data like feature flag payloads.
 *
 * For PostHog-specific types (surveys, replay events, etc.), use PostHogSerializer instead.
 */
@PostHogInternal
internal object PostHogGson {
    val gson: Gson by lazy { Gson() }
}

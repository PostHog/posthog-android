package com.posthog.internal

import kotlin.math.abs

/**
 * Simple hash function matching the JS SDK's simpleHash.
 * Produces a deterministic positive integer from a string.
 * See https://github.com/PostHog/posthog-js/blob/main/packages/browser/src/extensions/sampling.ts
 */
internal fun simpleHash(str: String): Int {
    var hash = 0
    for (char in str) {
        hash = (hash shl 5) - hash + char.code // (hash * 31) + char code
        hash = hash or 0 // keep as 32-bit integer
    }
    return abs(hash)
}

/**
 * Determines whether to sample based on a property string and a percentage (0..1).
 * Matches the JS SDK's sampleOnProperty logic.
 */
internal fun sampleOnProperty(prop: String, percent: Double): Boolean {
    val clampedPercent = (percent * 100).coerceIn(0.0, 100.0)
    return simpleHash(prop) % 100 < clampedPercent
}

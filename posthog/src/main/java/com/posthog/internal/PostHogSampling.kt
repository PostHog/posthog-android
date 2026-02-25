package com.posthog.internal

/**
 * Simple hash function matching the JS SDK's simpleHash.
 * Produces a deterministic positive integer from a string.
 * See https://github.com/PostHog/posthog-js/blob/main/packages/browser/src/extensions/sampling.ts
 */
internal fun simpleHash(str: String): Int {
    var hash = 0
    // Iterate UTF 16 code units, same as JS charCodeAt(i)
    for (i in 0 until str.length) {
        val codeUnit = str[i].code
        hash = (hash shl 5) - hash + codeUnit // hash * 31 + codeUnit, 32 bit wrap
        // no need for `hash = hash or 0`, Int is already 32 bit
    }
    // Match JS behavior safely, avoid abs(Int.MIN_VALUE) edge case
    return hash and Int.MAX_VALUE
}

/**
 * Determines whether to sample based on a property string and a percentage (0..1).
 * Matches the JS SDK's sampleOnProperty logic.
 */
internal fun sampleOnProperty(
    prop: String,
    percent: Double,
): Boolean {
    val clampedPercent = (percent * 100).coerceIn(0.0, 100.0)
    return simpleHash(prop) % 100 < clampedPercent
}

package com.posthog.internal

import kotlin.math.abs

/**
 * Simple hash function matching the JS SDK's simpleHash.
 * Produces a deterministic positive integer from a string.
 * See https://github.com/PostHog/posthog-js/blob/main/packages/browser/src/extensions/sampling.ts
 */
internal fun simpleHash(str: String): Long {
    var hash = 0
    // Iterate UTF 16 code units, same as JS charCodeAt(i)
    for (i in 0 until str.length) {
        val codeUnit = str[i].code
        hash = (hash shl 5) - hash + codeUnit // hash * 31 + codeUnit, 32 bit wrap
        // no need for `hash = hash or 0`, Int is already 32 bit
    }
    // JS returns Math.abs(hash), a double, so Int.MIN_VALUE maps to 2147483648 rather than to itself.
    return abs(hash.toLong())
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

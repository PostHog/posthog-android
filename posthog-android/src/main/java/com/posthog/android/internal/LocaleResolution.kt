package com.posthog.android.internal

import androidx.core.os.LocaleListCompat
import java.util.Locale

internal data class SupportedLocales(
    val locales: List<Locale>,
    val defaultLocale: Locale? = null,
)

/** Negotiates declared language support, not the language of every displayed resource. */
internal fun resolveLocale(
    preferences: List<Locale>,
    supported: SupportedLocales,
): Locale? {
    for (preferred in preferences) {
        val match =
            supported.locales.firstOrNull { it == preferred }
                ?: supported.locales.firstOrNull {
                    LocaleListCompat.matchesLanguageAndScript(it, preferred)
                }
        if (match != null) return match
    }
    return supported.defaultLocale?.takeIf { it in supported.locales }
}

internal fun LocaleListCompat.toLocales(): List<Locale> = (0 until size()).mapNotNull { this[it] }

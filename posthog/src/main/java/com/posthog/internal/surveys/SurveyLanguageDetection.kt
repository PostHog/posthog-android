package com.posthog.internal.surveys

import com.posthog.PostHogConfig
import com.posthog.PostHogInternal

/**
 * Resolves the language code to use for survey translation, in priority order:
 *
 *  1. Explicit SDK override (`PostHogSurveysConfig.overrideDisplayLanguage`).
 *  2. The `"language"` key of the persisted person properties (set via `identify(..., { language: "fr" })`).
 *  3. The device locale.
 *
 * Returns the first non-blank string from that chain, or `null` if all three are empty.
 */
@PostHogInternal
public fun detectSurveyLanguage(
    overrideLanguage: String?,
    personProperties: Map<String, Any?>?,
    deviceLocale: String?,
): String? {
    overrideLanguage?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }

    val personLanguage = personProperties?.get(PERSON_PROPERTY_LANGUAGE) as? String
    personLanguage?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }

    deviceLocale?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }

    return null
}

/**
 * Finds the best matching translation key in [translations] for [targetLanguage].
 *
 * Matching is case-insensitive. If no exact match is found and the target contains a
 * region suffix (e.g. `pt-BR`), the base language (e.g. `pt`) is tried as a fallback.
 *
 * Returns the original-cased key from [translations] (so it can be reported verbatim
 * as `$survey_language`), or `null` if no match.
 */
@PostHogInternal
public fun <T> findBestTranslationMatch(
    translations: Map<String, T>?,
    targetLanguage: String?,
): String? {
    if (translations.isNullOrEmpty() || targetLanguage.isNullOrBlank()) return null

    val normalizedTarget = targetLanguage.lowercase()

    translations.keys.firstOrNull { it.lowercase() == normalizedTarget }?.let { return it }

    val hyphenIndex = normalizedTarget.indexOf('-')
    if (hyphenIndex > 0) {
        val base = normalizedTarget.substring(0, hyphenIndex)
        translations.keys.firstOrNull { it.lowercase() == base }?.let { return it }
    }

    return null
}

/**
 * Reads the persisted person properties dictionary written by `identify()` and
 * `setPersonPropertiesForFlags()`. Returns an empty map when nothing is persisted.
 *
 * NOTE: the storage key here intentionally duplicates the value of the (internal)
 * `PostHogPreferences.PERSON_PROPERTIES_FOR_FLAGS` constant. Keeping it local avoids
 * widening the SDK's internal preference key API surface.
 */
@PostHogInternal
public fun readPersistedPersonProperties(config: PostHogConfig): Map<String, Any?> {
    val raw = config.cachePreferences?.getValue(PERSON_PROPERTIES_FOR_FLAGS_KEY) ?: return emptyMap()
    @Suppress("UNCHECKED_CAST")
    return (raw as? Map<String, Any?>) ?: emptyMap()
}

private const val PERSON_PROPERTY_LANGUAGE = "language"
private const val PERSON_PROPERTIES_FOR_FLAGS_KEY = "personPropertiesForFlags"

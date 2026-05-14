package com.posthog.surveys

import com.posthog.internal.surveys.detectSurveyLanguage
import com.posthog.internal.surveys.findBestTranslationMatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class SurveyLanguageDetectionTest {
    @Test
    fun `detectSurveyLanguage prefers explicit override`() {
        val result =
            detectSurveyLanguage(
                overrideLanguage = "fr",
                personProperties = mapOf("language" to "de"),
                deviceLocale = "en-US",
            )
        assertEquals("fr", result)
    }

    @Test
    fun `detectSurveyLanguage trims override whitespace`() {
        val result =
            detectSurveyLanguage(
                overrideLanguage = "  pt-BR  ",
                personProperties = null,
                deviceLocale = "en-US",
            )
        assertEquals("pt-BR", result)
    }

    @Test
    fun `detectSurveyLanguage falls back to person property when override blank`() {
        val result =
            detectSurveyLanguage(
                overrideLanguage = "   ",
                personProperties = mapOf("language" to "de"),
                deviceLocale = "en-US",
            )
        assertEquals("de", result)
    }

    @Test
    fun `detectSurveyLanguage falls back to device locale when both override and person prop blank`() {
        val result =
            detectSurveyLanguage(
                overrideLanguage = null,
                personProperties = mapOf("other" to "value"),
                deviceLocale = "en-US",
            )
        assertEquals("en-US", result)
    }

    @Test
    fun `detectSurveyLanguage returns null when nothing is set`() {
        val result =
            detectSurveyLanguage(
                overrideLanguage = null,
                personProperties = null,
                deviceLocale = null,
            )
        assertNull(result)
    }

    @Test
    fun `detectSurveyLanguage ignores non-string person language`() {
        val result =
            detectSurveyLanguage(
                overrideLanguage = null,
                personProperties = mapOf("language" to 42),
                deviceLocale = "en-US",
            )
        assertEquals("en-US", result)
    }

    @Test
    fun `findBestTranslationMatch returns exact match`() {
        val translations = mapOf("fr" to "x", "pt-BR" to "y")
        assertEquals("fr", findBestTranslationMatch(translations, "fr"))
        assertEquals("pt-BR", findBestTranslationMatch(translations, "pt-BR"))
    }

    @Test
    fun `findBestTranslationMatch is case insensitive and preserves original key casing`() {
        val translations = mapOf("Fr" to "x", "PT-br" to "y")
        assertEquals("Fr", findBestTranslationMatch(translations, "fr"))
        assertEquals("PT-br", findBestTranslationMatch(translations, "PT-BR"))
    }

    @Test
    fun `findBestTranslationMatch falls back to base language when target has hyphen`() {
        val translations = mapOf("pt" to "x")
        assertEquals("pt", findBestTranslationMatch(translations, "pt-BR"))
    }

    @Test
    fun `findBestTranslationMatch prefers exact match over base language`() {
        val translations = mapOf("pt" to "base", "pt-BR" to "exact")
        assertEquals("pt-BR", findBestTranslationMatch(translations, "pt-BR"))
    }

    @Test
    fun `findBestTranslationMatch does not fall back when target has no hyphen`() {
        val translations = mapOf("pt-BR" to "x")
        assertNull(findBestTranslationMatch(translations, "pt"))
    }

    @Test
    fun `findBestTranslationMatch returns null for empty inputs`() {
        val empty = emptyMap<String, String>()
        val nullMap: Map<String, String>? = null
        val single = mapOf("fr" to "x")
        assertNull(findBestTranslationMatch(empty, "fr"))
        assertNull(findBestTranslationMatch(nullMap, "fr"))
        assertNull(findBestTranslationMatch(single, null))
        assertNull(findBestTranslationMatch(single, ""))
        assertNull(findBestTranslationMatch(single, "  "))
    }
}

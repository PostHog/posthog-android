package com.posthog.android.internal

import android.util.Xml
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 32, 33, 35])
internal class LocaleResolutionTest {
    private fun locales(vararg tags: String) = tags.map(Locale::forLanguageTag)

    @Test
    fun `respects preference order and matches regional languages`() {
        val supported = SupportedLocales(locales("en", "de"))
        assertEquals(Locale.GERMAN, resolveLocale(locales("ru-RU", "de-CH", "en-US"), supported))
    }

    @Test
    fun `prefers exact support before broad regional matching`() {
        val supported = SupportedLocales(locales("en-US", "en-GB"))
        assertEquals(Locale.UK, resolveLocale(locales("en-GB"), supported))
    }

    @Test
    fun `preserves script distinctions`() {
        val supported = SupportedLocales(locales("zh-Hans", "zh-Hant"))
        assertEquals("zh-Hant", resolveLocale(locales("zh-TW"), supported)?.toLanguageTag())
        assertEquals("zh-Hans", resolveLocale(locales("zh-CN"), supported)?.toLanguageTag())
        assertNull(resolveLocale(locales("sr-Latn"), SupportedLocales(locales("sr-Cyrl"))))
    }

    @Test
    fun `pseudo locales require an exact match`() {
        assertNull(resolveLocale(locales("en-XA"), SupportedLocales(locales("en"))))
        assertEquals("en-XA", resolveLocale(locales("en-XA"), SupportedLocales(locales("en", "en-XA")))?.toLanguageTag())
    }

    @Test
    fun `uses declared fallback only when supported`() {
        assertEquals(Locale.ENGLISH, resolveLocale(locales("de-CH"), SupportedLocales(locales("en"), Locale.ENGLISH)))
        assertNull(resolveLocale(locales("de-CH"), SupportedLocales(locales("en"))))
        assertNull(resolveLocale(locales("ru"), SupportedLocales(locales("de"), Locale.ENGLISH)))
        assertNull(resolveLocale(locales("de"), SupportedLocales(emptyList())))
    }

    @Test
    @Config(sdk = [23])
    fun `matches basic language preferences on Android 6`() {
        assertEquals(Locale.GERMAN, resolveLocale(locales("de-CH"), SupportedLocales(locales("en", "de"))))
        assertEquals(Locale.ENGLISH, resolveLocale(locales("ru"), SupportedLocales(locales("en"), Locale.ENGLISH)))
    }

    private fun parse(xml: String): SupportedLocales? {
        val parser = Xml.newPullParser().apply { setInput(xml.reader()) }
        return parseLocaleConfig(parser) { error("Unexpected resource reference $it") }
    }

    @Test
    fun `parses declared locales and explicit default`() {
        val supported =
            parse(
                """
                <locale-config xmlns:android="http://schemas.android.com/apk/res/android" android:defaultLocale="en">
                <locale android:name="en"/>
                <locale android:name="de"/>
                </locale-config>
                """.trimIndent(),
            )
        assertEquals(SupportedLocales(locales("en", "de"), Locale.ENGLISH), supported)
    }

    @Test
    fun `does not infer default from declaration order`() {
        val supported =
            parse(
                """
                <locale-config xmlns:android="http://schemas.android.com/apk/res/android">
                <locale android:name="de"/>
                <locale android:name="en"/>
                </locale-config>
                """.trimIndent(),
            )
        assertEquals(SupportedLocales(locales("de", "en")), supported)
    }

    @Test
    fun `rejects invalid root and default`() {
        assertNull(
            parse(
                """
                <other xmlns:android="http://schemas.android.com/apk/res/android">
                <locale android:name="de"/>
                </other>
                """.trimIndent(),
            ),
        )
        assertNull(
            parse(
                """
                <locale-config xmlns:android="http://schemas.android.com/apk/res/android" android:defaultLocale="fr">
                <locale android:name="en"/>
                </locale-config>
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `ignores locales nested inside unknown elements`() {
        assertEquals(
            SupportedLocales(locales("en")),
            parse(
                """
                <locale-config xmlns:android="http://schemas.android.com/apk/res/android">
                <unknown>
                <locale android:name="de"/>
                </unknown>
                <locale android:name="en"/>
                </locale-config>
                """.trimIndent(),
            ),
        )
    }
}

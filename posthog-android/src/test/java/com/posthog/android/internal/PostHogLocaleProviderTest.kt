package com.posthog.android.internal

import android.app.LocaleConfig
import android.app.LocaleManager
import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.os.LocaleList
import android.util.Xml
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ApplicationProvider
import com.posthog.android.PostHogAndroidConfig
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 32])
internal class PostHogLocaleProviderTest {
    private val initialLocale = Locale.getDefault()
    private val initialAppLocales = AppCompatDelegate.getApplicationLocales()
    private val config = PostHogAndroidConfig("test")

    @AfterTest
    fun restoreLocales() {
        Locale.setDefault(initialLocale)
        AppCompatDelegate.setApplicationLocales(initialAppLocales)
    }

    @Test
    fun `dynamic event context uses declared app support`() {
        val context = declaredContext()
        val properties = PostHogAndroidContext(context, config, networkPropertiesProvider = { emptyMap() })
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ru-RU,de-CH,en-US"))
        assertEquals("de", properties.getDynamicContext()["\$locale"])
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en-US"))
        assertEquals("en", properties.getDynamicContext()["\$locale"])
    }

    @Test
    fun `falls back to declared default when preferences do not match`() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ru-RU"))
        assertEquals("en", PostHogLocaleProvider(declaredContext(), config).getLocale())
    }

    @Test
    fun `unmatched preferences without declared default retain existing value`() {
        val xml =
            """
            <locale-config xmlns:android="http://schemas.android.com/apk/res/android">
            <locale android:name="en"/>
            </locale-config>
            """.trimIndent()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ru-RU"))
        Locale.setDefault(Locale.forLanguageTag("de-CH"))
        assertEquals("de-CH", PostHogLocaleProvider(declaredContext(xml), config).getLocale())
    }

    @Test
    @Config(sdk = [23, 32, 33, 35])
    fun `missing declaration retains existing value including missing country`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = PostHogLocaleProvider(context, config)
        Locale.setDefault(Locale.forLanguageTag("de-CH"))
        assertEquals("de-CH", provider.getLocale())
        Locale.setDefault(Locale.ENGLISH)
        assertEquals("en-", provider.getLocale())
    }

    @Test
    fun `malformed declaration cannot prevent event context collection`() {
        Locale.setDefault(Locale.US)
        val context = declaredContext("<locale-config>")
        assertEquals("en-US", PostHogLocaleProvider(context, config).getLocale())
    }

    @Test
    fun `absent or older AppCompat is optional`() {
        assertTrue(OptionalAppCompatLocales("missing.AppCompatDelegate").getLocales().isEmpty())
        assertTrue(OptionalAppCompatLocales("java.lang.String").getLocales().isEmpty())
    }

    @Test
    fun `optional getter reads preference changes`() {
        val source = OptionalAppCompatLocales()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("de-CH,en-US"))
        assertEquals(listOf("de-CH", "en-US"), source.getLocales().map { it.toLanguageTag() })
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
        assertEquals(listOf("en"), source.getLocales().map { it.toLanguageTag() })
    }

    @Test
    @Config(sdk = [34, 35])
    fun `native preferences and runtime supported locales are refreshed`() {
        // Robolectric's LocaleManager shadow does not implement runtime locale-config overrides.
        val context = mock<Context>()
        val manager = mock<LocaleManager>()
        whenever(context.getSystemService(LocaleManager::class.java)).thenReturn(manager)
        whenever(manager.applicationLocales).thenReturn(LocaleList.forLanguageTags("de-CH,en-US"))
        whenever(manager.overrideLocaleConfig).thenReturn(LocaleConfig(LocaleList.forLanguageTags("en,de")))
        val provider = PostHogLocaleProvider(context, config)
        assertEquals("de", provider.getLocale())
        whenever(manager.overrideLocaleConfig).thenReturn(LocaleConfig(LocaleList.forLanguageTags("en")))
        assertEquals("en", provider.getLocale())
        whenever(manager.overrideLocaleConfig).thenReturn(null)
        Locale.setDefault(Locale.FRANCE)
        assertEquals("fr-FR", provider.getLocale())
    }

    private fun declaredContext(
        xml: String =
            """
            <locale-config xmlns:android="http://schemas.android.com/apk/res/android" android:defaultLocale="en">
            <locale android:name="en"/>
            <locale android:name="de"/>
            </locale-config>
            """.trimIndent(),
    ): Context {
        val context = mock<Context>()
        val assets = mock<AssetManager>()
        val resources = mock<Resources>()
        whenever(context.assets).thenReturn(assets)
        whenever(context.resources).thenReturn(resources)
        whenever(assets.openXmlResourceParser("AndroidManifest.xml")).thenAnswer {
            parser(
                """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application android:localeConfig="@xml/locales"/>
                </manifest>
                """.trimIndent(),
                123,
            )
        }
        whenever(resources.getXml(123)).thenAnswer { parser(xml) }
        return context
    }

    private fun parser(
        xml: String,
        manifestReference: Int = 0,
    ): XmlResourceParser {
        val source = Xml.newPullParser().apply { setInput(xml.reader()) }
        val parser = mock<XmlResourceParser>()
        whenever(parser.eventType).thenAnswer { source.eventType }
        whenever(parser.name).thenAnswer { source.name }
        whenever(parser.depth).thenAnswer { source.depth }
        whenever(parser.next()).thenAnswer { source.next() }
        for (name in listOf("localeConfig", "defaultLocale", "name")) {
            whenever(
                parser.getAttributeResourceValue("http://schemas.android.com/apk/res/android", name, 0),
            ).thenReturn(if (name == "localeConfig") manifestReference else 0)
            whenever(parser.getAttributeValue("http://schemas.android.com/apk/res/android", name)).thenAnswer {
                source.getAttributeValue("http://schemas.android.com/apk/res/android", name)
            }
        }
        return parser
    }
}

package com.posthog.android.internal

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
internal class PostHogAppLanguageTest {
    private fun resolve(
        preferred: String,
        supported: String,
        default: String? = null,
    ) = resolveAppLanguage(
        Locale.forLanguageTag(preferred),
        SupportedLocales(
            supported.split(",").map(Locale::forLanguageTag),
            default?.let(Locale::forLanguageTag),
        ),
    )

    @Test
    fun `resolves a regional preference to the declared language`() {
        assertEquals("de", resolve("de-CH", "en,de"))
    }

    @Test
    fun `prefers the exact regional match`() {
        assertEquals("pt-PT", resolve("pt-PT", "pt-BR,pt-PT"))
    }

    @Test
    fun `reports nothing when several relatives could match`() {
        assertNull(resolve("pt-MZ", "pt,pt-PT"))
        assertNull(resolve("pt-MZ", "pt-BR,pt-PT", default = "pt-BR"))
    }

    @Test
    fun `ignores regional preference extensions when matching`() {
        assertEquals("pt-PT", resolve("pt-PT-u-fw-mon", "pt-BR,pt-PT"))
    }

    @Test
    fun `does not cross scripts`() {
        assertEquals("zh-Hant", resolve("zh-Hant-TW", "zh-Hans,zh-Hant"))
        assertNull(resolve("zh-Hant-TW", "zh-Hans,en"))
    }

    @Test
    fun `uses the declared default when nothing matches`() {
        assertEquals("en", resolve("fr-FR", "de,en", default = "en"))
    }

    @Test
    fun `uses the only declared language when nothing matches`() {
        assertEquals("en", resolve("de-CH", "en"))
    }

    @Test
    fun `reports nothing when the fallback language is unknown`() {
        assertNull(resolve("fr-FR", "de,en"))
    }
}

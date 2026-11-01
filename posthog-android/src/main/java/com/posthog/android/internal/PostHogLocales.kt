package com.posthog.android.internal

import android.app.LocaleConfig
import android.app.LocaleManager
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import com.posthog.android.PostHogAndroidConfig
import java.util.Locale

/**
 * Reads `$locale` and `$app_language`.
 *
 * On Android 13+, Resources.getSystem() can report the per-app language and go stale, so the only
 * exact source is LocaleManager. Its Binder calls run once at setup, then [refresh] runs them off the
 * capture thread, so the event right after a language change can still carry the old value.
 */
internal class PostHogLocales(
    private val context: Context,
    private val config: PostHogAndroidConfig,
) {
    @Volatile
    private var systemLocales: LocaleList? = null

    @Volatile
    private var applicationLocales: LocaleList? = null

    private val supportedLocales: SupportedLocales? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) readSupportedLocales() else null
    }

    init {
        refresh()
    }

    fun refresh() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        try {
            val localeManager = context.getSystemService(LocaleManager::class.java) ?: return
            systemLocales = localeManager.systemLocales
            applicationLocales = localeManager.applicationLocales
        } catch (e: Throwable) {
            config.logger.log("Error reading the locales: $e.")
        }
    }

    fun deviceLocaleTag(): String? {
        val locale = systemLocales?.takeUnless { it.isEmpty }?.get(0) ?: resourcesLocale()
        return locale?.toLanguageTag()?.takeUnless { it == "und" }
    }

    /**
     * The language the app displays, on Android 13+. Android has no API for the language resources
     * resolved to, because the app context reports the preferred language even when the app falls
     * back to another. Android 6-12 can only read the declaration by parsing the manifest, so it
     * reports nothing rather than guessing.
     */
    fun appLanguage(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return null
        }
        val perApp = applicationLocales?.takeUnless { it.isEmpty }?.get(0)
        val supported = supportedLocales ?: return perApp?.toLanguageTag()
        val preferred = perApp ?: systemLocales?.takeUnless { it.isEmpty }?.get(0) ?: return null
        return resolveAppLanguage(preferred, supported)
    }

    @Suppress("DEPRECATION")
    private fun resourcesLocale(): Locale? {
        val configuration = Resources.getSystem().configuration
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) configuration.locales[0] else configuration.locale
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun readSupportedLocales(): SupportedLocales? {
        return try {
            val localeConfig = LocaleConfig(context)
            val locales = localeConfig.supportedLocales?.takeUnless { it.isEmpty } ?: return null
            val default =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) localeConfig.defaultLocale else null
            SupportedLocales(locales.toList(), default)
        } catch (e: Throwable) {
            config.logger.log("Error reading the locale config: $e.")
            null
        }
    }
}

internal class SupportedLocales(
    val locales: List<Locale>,
    val default: Locale?,
)

private fun LocaleList.toList(): List<Locale> = (0 until size()).map { get(it) }

/**
 * The declared form of the first preferred language: the exact tag, else the bare language, else
 * the first relative. It stops at the first preference because a library's translation of it makes
 * Android use that language, showing the app's default strings instead of a later preference. The
 * default's language is only known when declared or when the app declares just one.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun resolveAppLanguage(
    preferred: Locale,
    supported: SupportedLocales,
): String? {
    val candidates = supported.locales.filter { LocaleList.matchesLanguageAndScript(it, preferred) }
    val match =
        candidates.find { it == preferred }
            ?: candidates.find { it.country.isEmpty() }
            ?: candidates.firstOrNull()
            ?: supported.default
            ?: supported.locales.singleOrNull()
    return match?.toLanguageTag()
}

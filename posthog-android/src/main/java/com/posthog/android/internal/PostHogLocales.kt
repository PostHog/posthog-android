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
 * exact source is LocaleManager. Its Binder calls and the locale config parsing only run in [refresh],
 * on a worker thread, and capture reads the last values. So the event right after a language change
 * can still carry the old value, and events before the first refresh carry the Resources language
 * and no app language.
 */
internal class PostHogLocales(
    private val context: Context,
    private val config: PostHogAndroidConfig,
) {
    @Volatile
    private var systemLocales: LocaleList? = null

    @Volatile
    private var applicationLocales: LocaleList? = null

    @Volatile
    private var supportedLocales: SupportedLocales? = null

    // parsed once, because refresh runs as often as events are captured
    private val declaredLocales: SupportedLocales? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) readDeclaredLocales() else null
    }

    fun refresh() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        try {
            val localeManager = context.getSystemService(LocaleManager::class.java) ?: return
            systemLocales = localeManager.systemLocales
            applicationLocales = localeManager.applicationLocales
            if (config.detectAppLanguage) {
                supportedLocales = readSupportedLocales(localeManager)
            }
        } catch (e: Throwable) {
            config.logger.log("Error reading the locales: $e.")
        }
    }

    fun deviceLocaleTag(): String? {
        val locale = systemLocales?.takeUnless { it.isEmpty }?.get(0) ?: resourcesLocale()
        return locale?.withoutExtensions()?.toLanguageTag()?.takeUnless { it == "und" }
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
        val supported = supportedLocales ?: return perApp?.withoutExtensions()?.toLanguageTag()
        val preferred = perApp ?: systemLocales?.takeUnless { it.isEmpty }?.get(0) ?: return null
        return resolveAppLanguage(preferred, supported)
    }

    @Suppress("DEPRECATION")
    private fun resourcesLocale(): Locale? {
        val configuration = Resources.getSystem().configuration
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) configuration.locales[0] else configuration.locale
    }

    // Android 14+ apps can change their supported locales at runtime with setOverrideLocaleConfig
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun readSupportedLocales(localeManager: LocaleManager): SupportedLocales? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            localeManager.overrideLocaleConfig?.let { return it.toSupportedLocales() }
        }
        return declaredLocales
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun readDeclaredLocales(): SupportedLocales? {
        return try {
            val localeConfig =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    LocaleConfig.fromContextIgnoringOverride(context)
                } else {
                    LocaleConfig(context)
                }
            localeConfig.toSupportedLocales()
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

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun LocaleConfig.toSupportedLocales(): SupportedLocales? {
    val locales = supportedLocales?.takeUnless { it.isEmpty } ?: return null
    val default = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) defaultLocale else null
    return SupportedLocales(locales.toList(), default)
}

private fun LocaleList.toList(): List<Locale> = (0 until size()).map { get(it) }

// Android 14+ regional preferences add extensions such as en-US-u-fw-mon.
private fun Locale.withoutExtensions(): Locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) stripExtensions() else this

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
    val language = preferred.withoutExtensions()
    val candidates = supported.locales.filter { LocaleList.matchesLanguageAndScript(it, language) }
    val match =
        candidates.find { it == language }
            ?: candidates.find { it.country.isEmpty() }
            ?: candidates.firstOrNull()
            ?: supported.default
            ?: supported.locales.singleOrNull()
    return match?.toLanguageTag()
}

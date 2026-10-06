package com.posthog.android.internal

import android.app.LocaleConfig
import android.app.LocaleManager
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.util.AttributeSet
import androidx.annotation.RequiresApi
import androidx.core.os.ConfigurationCompat
import androidx.core.os.LocaleListCompat
import com.posthog.android.PostHogAndroidConfig
import org.xmlpull.v1.XmlPullParser
import java.util.Locale

/** Reads packaged declarations once, but refreshes preferences and runtime overrides per event. */
internal class PostHogLocaleProvider(
    private val context: Context,
    private val config: PostHogAndroidConfig,
) {
    private val manifestSupport by lazy {
        try {
            readManifestLocaleConfig(context)
        } catch (e: Throwable) {
            config.logger.log("Unable to read the app locale declaration: $e.")
            null
        }
    }
    private val platformSupport by lazy {
        if (Build.VERSION.SDK_INT >= 33) readPlatformSupport() else null
    }
    private val appCompatLocales = OptionalAppCompatLocales()

    fun getLocale(): String {
        try {
            val supported = if (Build.VERSION.SDK_INT >= 33) currentPlatformSupport() else manifestSupport
            if (supported != null) {
                resolveLocale(requestedLocales(), supported)?.let { return it.toLanguageTag() }
            }
        } catch (e: Throwable) {
            config.logger.log("Unable to resolve the app locale: $e.")
        }
        val locale = Locale.getDefault()
        return "${locale.language}-${locale.country}"
    }

    private fun requestedLocales(): List<Locale> {
        if (Build.VERSION.SDK_INT >= 33) {
            val manager = context.getSystemService(LocaleManager::class.java)
            val app = manager.applicationLocales
            val locales = if (!app.isEmpty) app else manager.systemLocales
            return (0 until locales.size()).map { locales[it] }
        }
        return appCompatLocales.getLocales().ifEmpty {
            ConfigurationCompat.getLocales(Resources.getSystem().configuration).toLocales()
        }
    }

    @RequiresApi(33)
    private fun readPlatformSupport(): SupportedLocales? {
        val localeConfig =
            if (Build.VERSION.SDK_INT >= 34) {
                LocaleConfig.fromContextIgnoringOverride(context)
            } else {
                LocaleConfig(context)
            }
        if (localeConfig.status != LocaleConfig.STATUS_SUCCESS) return null
        val locales = localeConfig.supportedLocales ?: return null
        val supported = (0 until locales.size()).map { locales[it] }
        val declared = manifestSupport
        val defaultLocale = declared?.takeIf { it.locales.toSet() == supported.toSet() }?.defaultLocale
        return SupportedLocales(supported, defaultLocale)
    }

    @RequiresApi(33)
    private fun currentPlatformSupport(): SupportedLocales? {
        if (Build.VERSION.SDK_INT >= 34) {
            val override = context.getSystemService(LocaleManager::class.java).overrideLocaleConfig
            if (override != null) {
                // A runtime override must not inherit the packaged declaration's default.
                val locales = override.supportedLocales ?: return null
                val defaultLocale = if (Build.VERSION.SDK_INT >= 35) override.defaultLocale else null
                return SupportedLocales((0 until locales.size()).map { locales[it] }, defaultLocale)
            }
        }
        return platformSupport
    }
}

/** Access AppCompat's public getter without making AppCompat a runtime dependency. */
internal class OptionalAppCompatLocales(
    private val className: String = "androidx.appcompat.app.AppCompatDelegate",
) {
    private val getter by lazy {
        try {
            Class.forName(className, false, javaClass.classLoader).getMethod("getApplicationLocales")
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: LinkageError) {
            null
        }
    }

    fun getLocales(): List<Locale> {
        return try {
            (getter?.invoke(null) as? LocaleListCompat)?.toLocales().orEmpty()
        } catch (_: Exception) {
            emptyList()
        } catch (_: LinkageError) {
            emptyList()
        }
    }
}

private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"

internal fun readManifestLocaleConfig(context: Context): SupportedLocales? {
    val resourceId =
        context.assets.openXmlResourceParser("AndroidManifest.xml").use { parser ->
            var id = 0
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "application") {
                    id = parser.getAttributeResourceValue(ANDROID_NAMESPACE, "localeConfig", 0)
                    break
                }
                parser.next()
            }
            id
        }
    if (resourceId == 0) return null
    return context.resources.getXml(resourceId).use { parseLocaleConfig(it, context::getString) }
}

internal fun parseLocaleConfig(
    parser: XmlPullParser,
    resolveString: (Int) -> String,
): SupportedLocales? {
    fun attribute(name: String): String? {
        val reference = (parser as? AttributeSet)?.getAttributeResourceValue(ANDROID_NAMESPACE, name, 0) ?: 0
        return if (reference != 0) resolveString(reference) else parser.getAttributeValue(ANDROID_NAMESPACE, name)
    }

    while (parser.eventType != XmlPullParser.START_TAG && parser.eventType != XmlPullParser.END_DOCUMENT) parser.next()
    if (parser.eventType != XmlPullParser.START_TAG || parser.name != "locale-config") return null
    val rootDepth = parser.depth
    val defaultLocale = attribute("defaultLocale")?.let { Locale.Builder().setLanguageTag(it).build() }
    val locales = mutableListOf<Locale>()
    while (parser.next() != XmlPullParser.END_DOCUMENT) {
        if (parser.eventType == XmlPullParser.END_TAG && parser.depth == rootDepth) break
        if (parser.eventType == XmlPullParser.START_TAG && parser.depth == rootDepth + 1 && parser.name == "locale") {
            val tag = attribute("name") ?: return null
            val locale = Locale.Builder().setLanguageTag(tag).build()
            if (locale.language.isEmpty()) return null
            locales.add(locale)
        }
    }
    if (defaultLocale != null && defaultLocale !in locales) return null
    return SupportedLocales(locales.distinct(), defaultLocale)
}

package com.posthog.android.internal

import android.content.Context
import com.posthog.android.PostHogAndroidConfig
import java.io.BufferedInputStream
import java.io.FileNotFoundException
import java.util.Properties

internal class PostHogMetaPropertiesApplier() {
    private fun loadMetaProperties(
        context: Context,
        config: PostHogAndroidConfig,
    ): List<Properties>? {
        val assets = context.assets

        // one may have thousands of asset files and looking up this list might slow down the SDK init.
        // quite a bit, for this reason, we try to open the file directly and take care of errors
        // like FileNotFoundException
        try {
            BufferedInputStream(assets.open(POSTHOG_META_PROPERTIES_OUTPUT)).use { `is` ->
                val properties = Properties()
                properties.load(`is`)
                return listOf(properties)
            }
        } catch (e: FileNotFoundException) {
            // ignore
        } catch (e: Throwable) {
            config.logger.log("Failed reading the meta properties: $e.")
        }

        return null
    }

    fun applyToConfig(
        context: Context,
        config: PostHogAndroidConfig,
        releaseIdentifierFallback: String,
    ) {
        val metaProperties = loadMetaProperties(context, config)

        // if releaseIdentifier is already set, we don't need to do anything
        if (!config.releaseIdentifier.isNullOrEmpty() || metaProperties.isNullOrEmpty()) {
            config.logger.log("releaseIdentifier not found, using fallback: $releaseIdentifierFallback")
            config.releaseIdentifier = releaseIdentifierFallback
            return
        }

        for (property in metaProperties) {
            val uuid = property.getProperty(POSTHOG_PROGUARD_MAPPING_MAP_ID_PROPERTY)

            if (uuid.isNullOrEmpty()) {
                continue
            }

            config.logger.log("releaseIdentifier found: $uuid")
            config.releaseIdentifier = uuid
            break
        }
    }

    companion object {
        private const val POSTHOG_PROGUARD_MAPPING_MAP_ID_PROPERTY = "io.posthog.proguard.mapid"
        private const val POSTHOG_META_PROPERTIES_OUTPUT = "posthog-meta.properties"
    }
}

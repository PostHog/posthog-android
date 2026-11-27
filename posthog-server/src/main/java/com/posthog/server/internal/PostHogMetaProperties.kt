package com.posthog.server.internal

import com.posthog.internal.PostHogLogger
import com.posthog.server.PostHog
import java.util.Properties

internal const val POSTHOG_META_PROPERTIES_FILE = "posthog-meta.properties"
internal const val POSTHOG_MAP_ID_PROPERTY = "io.posthog.proguard.mapid"

/**
 * Reads the map id that the build wrote into [POSTHOG_META_PROPERTIES_FILE], so exception frames
 * carry the id the build's symbols (a ProGuard mapping or a source bundle) were uploaded under.
 * The posthog-maven-plugin, or `posthog-cli jvm upload --meta-out`, writes the file into the
 * application's resources.
 *
 * The context class loader comes first because application servers load each application with
 * its own loader, and the SDK's loader may be a shared parent that cannot see the file.
 * Returns null when no loader has the file or the value is blank. Never throws.
 */
internal fun loadMapIdFromClasspath(
    logger: PostHogLogger,
    classLoaders: List<ClassLoader?> =
        listOf(Thread.currentThread().contextClassLoader, PostHog::class.java.classLoader),
): String? {
    for (classLoader in classLoaders.filterNotNull().distinct()) {
        val mapId =
            try {
                classLoader.getResourceAsStream(POSTHOG_META_PROPERTIES_FILE)?.use { stream ->
                    Properties().apply { load(stream) }.getProperty(POSTHOG_MAP_ID_PROPERTY)?.trim()
                }
            } catch (e: Throwable) {
                logger.log("Failed reading $POSTHOG_META_PROPERTIES_FILE: $e.")
                null
            }
        if (!mapId.isNullOrEmpty()) {
            logger.log("releaseIdentifier found in $POSTHOG_META_PROPERTIES_FILE: $mapId")
            return mapId
        }
    }

    logger.log("No $POSTHOG_MAP_ID_PROPERTY in $POSTHOG_META_PROPERTIES_FILE on the classpath, so exception frames carry no map_id.")
    return null
}

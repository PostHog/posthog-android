package com.posthog.server.internal

import com.posthog.internal.PostHogLogger
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PostHogMetaPropertiesTest {
    private val tempDirs = mutableListOf<File>()
    private val logger = RecordingLogger()

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun `reads the map id from the first class loader that has the file`() {
        val loader = loaderWith("io.posthog.proguard.mapid=3f9a1c2e-0000-5000-8000-000000000001\n")

        assertEquals("3f9a1c2e-0000-5000-8000-000000000001", loadMapIdFromClasspath(logger, listOf(loader)))
        assertTrue(logger.messages.any { it.contains("3f9a1c2e-0000-5000-8000-000000000001") })
    }

    @Test
    fun `falls back to the next class loader when the first has no file`() {
        val empty = loaderWith(null)
        val withFile = loaderWith("io.posthog.proguard.mapid=fallback-id\n")

        assertEquals("fallback-id", loadMapIdFromClasspath(logger, listOf(empty, withFile)))
    }

    @Test
    fun `skips a blank value and keeps looking`() {
        val blank = loaderWith("io.posthog.proguard.mapid=   \n")
        val withFile = loaderWith("io.posthog.proguard.mapid=  trimmed-id  \n")

        assertEquals("trimmed-id", loadMapIdFromClasspath(logger, listOf(blank, withFile)))
    }

    @Test
    fun `returns null when no loader has the key`() {
        val otherKey = loaderWith("some.other.key=value\n")

        assertNull(loadMapIdFromClasspath(logger, listOf(null, otherKey, loaderWith(null))))
        assertTrue(logger.messages.any { it.contains("carry no map_id") })
    }

    private fun loaderWith(content: String?): ClassLoader {
        val dir = Files.createTempDirectory("posthog-meta").toFile().also { tempDirs.add(it) }
        if (content != null) {
            File(dir, POSTHOG_META_PROPERTIES_FILE).writeText(content)
        }
        // No parent, so the loader cannot see a posthog-meta.properties on the test classpath.
        return URLClassLoader(arrayOf(dir.toURI().toURL()), null)
    }

    private class RecordingLogger : PostHogLogger {
        val messages = mutableListOf<String>()

        override fun log(message: String) {
            messages.add(message)
        }

        override fun isEnabled(): Boolean = true
    }
}

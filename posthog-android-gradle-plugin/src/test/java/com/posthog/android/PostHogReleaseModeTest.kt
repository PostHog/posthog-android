package com.posthog.android

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal class PostHogReleaseModeTest {
    // Not `apply`: Project has its own apply(), so the scope function would resolve to Gradle's.
    private fun project(releaseMode: String? = null): Project {
        val project = ProjectBuilder.builder().build()
        releaseMode?.let { project.extensions.extraProperties.set(POSTHOG_RELEASE_MODE_PROPERTY, it) }
        return project
    }

    @Test
    fun `a build that configures nothing uploads its mapping release-independent`() {
        assertEquals(PostHogReleaseMode.EVENT, resolvePostHogReleaseMode(project(), emptyMap()))
    }

    @Test
    fun `the gradle property selects the mode`() {
        assertEquals(
            PostHogReleaseMode.SYMBOL_SET,
            resolvePostHogReleaseMode(project(releaseMode = "symbol-set"), emptyMap()),
        )
    }

    @Test
    fun `the environment variable selects the mode`() {
        assertEquals(
            PostHogReleaseMode.SYMBOL_SET,
            resolvePostHogReleaseMode(project(), mapOf(POSTHOG_RELEASE_MODE_ENV to "symbol-set")),
        )
    }

    @Test
    fun `the gradle property wins over the environment variable`() {
        assertEquals(
            PostHogReleaseMode.SYMBOL_SET,
            resolvePostHogReleaseMode(
                project(releaseMode = "symbol-set"),
                mapOf(POSTHOG_RELEASE_MODE_ENV to "event"),
            ),
        )
    }

    @Test
    fun `an unrecognized value fails the build instead of picking a mode`() {
        assertFailsWith<IllegalStateException> {
            resolvePostHogReleaseMode(project(releaseMode = "symbolset"), emptyMap())
        }
    }
}

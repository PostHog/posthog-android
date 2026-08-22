package com.posthog.android

import org.gradle.api.logging.Logging
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals

internal class PostHogCliResolverTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val logger = Logging.getLogger(PostHogCliResolverTest::class.java)

    @Test
    fun `prefers React Native project-local CLI over PATH`() {
        val reactNativeRoot = temporaryFolder.newFolder("project")
        val androidRoot = File(reactNativeRoot, "android").apply { mkdirs() }
        val localCli = createLauncher(reactNativeRoot, "posthog-cli", executable = true)
        val pathDirectory = temporaryFolder.newFolder("global-bin")
        createExecutable(File(pathDirectory, "posthog-cli"))

        val resolved =
            resolvePostHogCliExecutable(
                configured = POSTHOG_CLI_DEFAULT_EXECUTABLE,
                logger = logger,
                environment = mapOf("PATH" to pathDirectory.absolutePath),
                home = temporaryFolder.newFolder("home"),
                workingDirectory = androidRoot,
                isWindows = false,
            )

        assertEquals(localCli.absolutePath, resolved)
    }

    @Test
    fun `finds project-local CLI inside the Gradle root`() {
        val androidRoot = temporaryFolder.newFolder("android")
        val localCli = createLauncher(androidRoot, "posthog-cli", executable = true)

        val resolved =
            resolvePostHogCliExecutable(
                configured = POSTHOG_CLI_DEFAULT_EXECUTABLE,
                logger = logger,
                environment = emptyMap(),
                home = temporaryFolder.newFolder("home"),
                workingDirectory = androidRoot,
                isWindows = false,
            )

        assertEquals(localCli.absolutePath, resolved)
    }

    @Test
    fun `uses the Windows npm launcher and command shell`() {
        val reactNativeRoot = temporaryFolder.newFolder("project")
        val androidRoot = File(reactNativeRoot, "android").apply { mkdirs() }
        val localCli = createLauncher(reactNativeRoot, "posthog-cli.cmd", executable = false)

        val resolved =
            resolvePostHogCliExecutable(
                configured = POSTHOG_CLI_DEFAULT_EXECUTABLE,
                logger = logger,
                environment = emptyMap(),
                home = temporaryFolder.newFolder("home"),
                workingDirectory = androidRoot,
                isWindows = true,
            )

        assertEquals(localCli.absolutePath, resolved)
        assertEquals(
            listOf("cmd", "/c", localCli.absolutePath, "exp", "proguard", "upload"),
            buildPostHogCliCommandLine(
                executable = resolved,
                arguments = listOf("exp", "proguard", "upload"),
                isWindows = true,
            ),
        )
    }

    @Test
    fun `keeps an explicitly configured executable`() {
        val customExecutable = temporaryFolder.newFile("custom-posthog-cli").absolutePath

        val resolved =
            resolvePostHogCliExecutable(
                configured = customExecutable,
                logger = logger,
                environment = emptyMap(),
                home = temporaryFolder.newFolder("home"),
                workingDirectory = temporaryFolder.newFolder("android"),
                isWindows = false,
            )

        assertEquals(customExecutable, resolved)
    }

    private fun createLauncher(
        projectRoot: File,
        name: String,
        executable: Boolean,
    ): File {
        val launcher = File(projectRoot, "node_modules/.bin/$name")
        launcher.parentFile.mkdirs()
        launcher.writeText("launcher")
        if (executable) {
            launcher.setExecutable(true)
        }
        return launcher
    }

    private fun createExecutable(file: File): File {
        file.writeText("#!/bin/sh\n")
        file.setExecutable(true)
        return file
    }
}

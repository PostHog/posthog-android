package com.posthog.android

import org.gradle.api.logging.Logging
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        createExecutable(File(pathDirectory, "node"))

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
        val home = temporaryFolder.newFolder("home")
        createExecutable(File(home, ".nvm/versions/node/v22.0.0/bin/node"))

        val resolved =
            resolvePostHogCliExecutable(
                configured = POSTHOG_CLI_DEFAULT_EXECUTABLE,
                logger = logger,
                environment = emptyMap(),
                home = home,
                workingDirectory = androidRoot,
                isWindows = false,
            )

        assertEquals(localCli.absolutePath, resolved)
    }

    @Test
    fun `executes a local npm launcher when Node is absent from the initial PATH`() {
        assumeFalse(System.getProperty("os.name").contains("windows", ignoreCase = true))
        val reactNativeRoot = temporaryFolder.newFolder("project")
        val androidRoot = File(reactNativeRoot, "android").apply { mkdirs() }
        val localCli = createLauncher(reactNativeRoot, "posthog-cli", executable = true)
        localCli.writeText("#!/usr/bin/env node\n")
        val home = temporaryFolder.newFolder("home")
        val marker = File(temporaryFolder.root, "node-invoked")
        val node = createExecutable(File(home, ".nvm/versions/node/v22.0.0/bin/node"))
        node.writeText("#!/bin/sh\necho invoked > '${marker.absolutePath}'\n")

        val resolved =
            resolvePostHogCliExecutable(
                configured = POSTHOG_CLI_DEFAULT_EXECUTABLE,
                logger = logger,
                environment = emptyMap(),
                home = home,
                workingDirectory = androidRoot,
                isWindows = false,
                nodeInstallLocations = listOf(node),
            )
        val (_, path) =
            prependExecutableDirectoriesToPath(
                listOfNotNull(
                    resolved,
                    resolveNodeExecutable(emptyMap(), home, isWindows = false, knownInstallLocations = listOf(node)),
                ),
                emptyMap(),
            )
        val process = ProcessBuilder(resolved).apply { environment()["PATH"] = path }.start()

        assertEquals(0, process.waitFor())
        assertTrue(marker.isFile)
    }

    @Test
    fun `falls back to a global CLI when a local launcher cannot find Node`() {
        val androidRoot = temporaryFolder.newFolder("android")
        createLauncher(androidRoot, "posthog-cli", executable = true)
        val home = temporaryFolder.newFolder("home")
        val globalCli = createExecutable(File(home, ".local/bin/posthog-cli"))

        val resolved =
            resolvePostHogCliExecutable(
                configured = POSTHOG_CLI_DEFAULT_EXECUTABLE,
                logger = logger,
                environment = emptyMap(),
                home = home,
                workingDirectory = androidRoot,
                isWindows = false,
                nodeInstallLocations = emptyList(),
            )

        assertEquals(globalCli.absolutePath, resolved)
    }

    @Test
    fun `uses the Windows npm launcher and command shell`() {
        val reactNativeRoot = temporaryFolder.newFolder("project")
        val androidRoot = File(reactNativeRoot, "android").apply { mkdirs() }
        val localCli = createLauncher(reactNativeRoot, "posthog-cli.cmd", executable = false)
        val nodeDirectory = temporaryFolder.newFolder("node")
        createLauncherFile(File(nodeDirectory, "node.exe"), executable = false)

        val resolved =
            resolvePostHogCliExecutable(
                configured = POSTHOG_CLI_DEFAULT_EXECUTABLE,
                logger = logger,
                environment = mapOf("Path" to nodeDirectory.absolutePath),
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
    fun `preserves the case and value of the Windows Path variable`() {
        val executable = File(temporaryFolder.newFolder("node_modules", ".bin"), "posthog-cli.cmd")
        val node = File(temporaryFolder.newFolder("node"), "node.exe")
        val existingPath = "C:\\Windows"

        val path =
            prependExecutableDirectoriesToPath(
                executables = listOf(executable.absolutePath, node.absolutePath),
                environment = mapOf("Path" to existingPath),
            )

        assertEquals(
            "Path" to listOf(executable.parent, node.parent, existingPath).joinToString(File.pathSeparator),
            path,
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

    private fun createExecutable(file: File): File = createLauncherFile(file, executable = true)

    private fun createLauncherFile(
        file: File,
        executable: Boolean,
    ): File {
        file.parentFile.mkdirs()
        file.writeText("#!/bin/sh\n")
        if (executable) {
            file.setExecutable(true)
        }
        return file
    }
}

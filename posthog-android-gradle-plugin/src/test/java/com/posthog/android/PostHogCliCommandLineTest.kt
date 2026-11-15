package com.posthog.android

import org.junit.Test
import kotlin.test.assertEquals

internal class PostHogCliCommandLineTest {
    private val args = listOf("exp", "proguard", "upload", "--path", "mapping.txt")

    @Test
    fun `runs the CLI directly outside Windows`() {
        assertEquals(
            listOf("/usr/local/bin/posthog-cli") + args,
            postHogCliCommandLine("/usr/local/bin/posthog-cli", args, isWindows = false),
        )
    }

    @Test
    fun `runs a bare CLI name through cmd on Windows`() {
        // cmd resolves posthog-cli to posthog-cli.exe or the npm posthog-cli.cmd shim
        assertEquals(
            listOf("cmd", "/c", "posthog-cli") + args,
            postHogCliCommandLine("posthog-cli", args, isWindows = true),
        )
    }

    @Test
    fun `runs an exe directly on Windows`() {
        val exe = "C:\\Program Files\\PostHog\\posthog-cli.EXE"
        assertEquals(
            listOf(exe) + args,
            postHogCliCommandLine(exe, args, isWindows = true),
        )
    }
}

package com.posthog.android

import org.gradle.api.GradleException
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun `refuses an argument that cmd would run or expand`() {
        for (unsafe in listOf("v1&whoami", "C:\\work\\R&D\\src", "a|b", "a>b", "%PATH%", "!x!", "say \"hi\"", "a^b", "a\nb")) {
            assertEquals(unsafe, firstUnsafeForCmd(listOf("--release-version", unsafe)))
            val error =
                assertFailsWith<GradleException> {
                    postHogCliCommandLine("posthog-cli", listOf("--release-version", unsafe), isWindows = true)
                }
            assertTrue(error.message!!.contains("posthog-cli.exe"), error.message)
        }
        assertNull(firstUnsafeForCmd(listOf("C:\\My Projects\\app (1)\\src", "1.2.3+build.4", "1.0(5)")))
        // An exe runs without cmd, so the same value reaches it unchanged.
        assertEquals(
            listOf("C:\\posthog-cli.exe", "--release-version", "v1&whoami"),
            postHogCliCommandLine("C:\\posthog-cli.exe", listOf("--release-version", "v1&whoami"), isWindows = true),
        )
    }
}

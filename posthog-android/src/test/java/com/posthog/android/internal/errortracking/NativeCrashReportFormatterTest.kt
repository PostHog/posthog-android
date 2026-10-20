package com.posthog.android.internal.errortracking

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class NativeCrashReportFormatterTest {
    private val crashedThread =
        NativeCrashThread(
            id = 4343,
            name = "RenderThread",
            registers =
                listOf(
                    "x0" to 0x0L,
                    "x1" to 0x1L,
                    "x2" to 0x2L,
                    "x3" to 0x3L,
                    "x4" to 0x4L,
                    "sp" to 0x7ffc0L,
                    "pc" to 0x7a12345103d8L,
                ),
            backtraceNotes = emptyList(),
            frames =
                listOf(
                    NativeCrashFrame(
                        0x103d8,
                        0x7a12345103d8,
                        "process_frame",
                        12,
                        "/data/app/~~abc/lib/arm64/libengine.so",
                        "524acc06c9af",
                    ),
                    NativeCrashFrame(0x2040, 0x7a1234502040, null, 0, "/data/app/~~abc/base.apk", null, fileMapOffset = 0x1000),
                ),
        )
    private val otherThread =
        NativeCrashThread(
            id = 1,
            name = "main",
            registers = listOf("x0" to 0x10L, "pc" to 0x7b0000001000L),
            backtraceNotes = listOf("frames may be missing"),
            frames =
                listOf(
                    NativeCrashFrame(0x4a6c0, 0x7b000004a6c0, "__epoll_pwait", 8, "/apex/com.android.runtime/lib64/bionic/libc.so", "ffee"),
                ),
        )

    private fun tombstone(threads: Map<Long, NativeCrashThread>) =
        NativeCrashTombstone(
            arch = "arm64",
            tid = 4343,
            signalName = "SIGSEGV",
            signalCodeName = "SEGV_MAPERR",
            // address 0 is left off the wire, so only the flag says a fault address exists
            faultAddress = null,
            hasFaultAddress = true,
            abortMessage = null,
            frames = crashedThread.frames,
            buildFingerprint = "google/sdk_gphone64_arm64/emu64a:15/AE3A/1:userdebug/dev-keys",
            revision = "0",
            timestamp = "2026-10-20 23:10:00.000000000+0000",
            pid = 4242,
            uid = 10123,
            processUptime = 12,
            pageSize = 16384,
            commandLine = listOf("com.example.app"),
            signalNumber = 11,
            signalCode = 1,
            causes = listOf("null pointer dereference"),
            threads = threads,
        )

    @Test
    fun `renders the crashing thread with its registers first, then the others by id, in debuggerd's format`() {
        val text = NativeCrashReportFormatter.format(tombstone(mapOf(4343L to crashedThread, 1L to otherThread)))

        val expected =
            """
            *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***
            Build fingerprint: 'google/sdk_gphone64_arm64/emu64a:15/AE3A/1:userdebug/dev-keys'
            Revision: '0'
            ABI: 'arm64'
            Timestamp: 2026-10-20 23:10:00.000000000+0000
            Process uptime: 12s
            Page size: 16384 bytes
            Cmdline: com.example.app
            pid: 4242, tid: 4343, name: RenderThread  >>> com.example.app <<<
            uid: 10123
            signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0000000000000000
            Cause: null pointer dereference
                x0  0000000000000000  x1  0000000000000001  x2  0000000000000002  x3  0000000000000003
                x4  0000000000000004
                sp  000000000007ffc0  pc  00007a12345103d8

            2 total frames
            backtrace:
                  #00 pc 00000000000103d8  /data/app/~~abc/lib/arm64/libengine.so (process_frame+12) (BuildId: 524acc06c9af)
                  #01 pc 0000000000002040  /data/app/~~abc/base.apk (offset 0x1000)
            --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- ---
            Cmdline: com.example.app
            pid: 4242, tid: 1, name: main  >>> com.example.app <<<
            uid: 10123

            1 total frames
            backtrace:
              NOTE: frames may be missing
                  #00 pc 000000000004a6c0  /apex/com.android.runtime/lib64/bionic/libc.so (__epoll_pwait+8) (BuildId: ffee)

            """.trimIndent()
        assertEquals(expected, text)
    }

    @Test
    fun `returns null without an entry for the crashing thread`() {
        assertNull(NativeCrashReportFormatter.format(tombstone(mapOf(1L to otherThread))))
    }

    @Test
    fun `lists every cause after the backtrace when there are several`() {
        val text =
            NativeCrashReportFormatter.format(
                tombstone(mapOf(4343L to crashedThread)).copy(causes = listOf("use-after-free", "buffer overflow")),
            )!!

        assertFalse(text.contains("Cause: use-after-free\n    x0"))
        val expectedTail =
            """
            Note: multiple potential causes for this crash were detected, listing them in decreasing order of likelihood.

            Cause: use-after-free

            Cause: buffer overflow

            """.trimIndent()
        assertTrue(text.endsWith(expectedTail), text)
    }
}

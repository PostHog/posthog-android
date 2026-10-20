package com.posthog.android.internal.errortracking

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class TombstoneParserTest {
    private fun frame(
        relPc: Long,
        pc: Long,
        function: String?,
        functionOffset: Long,
        file: String?,
        buildId: String?,
    ): TestProtoWriter.() -> Unit =
        {
            varint(1, relPc)
            varint(2, pc)
            function?.let { string(4, it) }
            varint(5, functionOffset)
            file?.let { string(6, it) }
            buildId?.let { string(8, it) }
        }

    @Test
    fun `parses signal info, abort message, and the crashing thread's backtrace`() {
        val tombstone =
            TestProtoWriter()
                .varint(1, 1) // arch = ARM64
                .varint(5, 4242) // pid
                .varint(6, 4343) // tid
                .message(10) {
                    // signal_info
                    varint(1, 11)
                    string(2, "SIGSEGV")
                    varint(3, 1)
                    string(4, "SEGV_MAPERR")
                    varint(8, 1) // has_fault_address
                    varint(9, 0xdeadL) // fault_address
                }
                .message(16) {
                    // threads[4343] — the crashing one
                    varint(1, 4343)
                    message(2) {
                        varint(1, 4343)
                        string(2, "RenderThread")
                        message(4, frame(0x103d8, 0x7a12345103d8, "process_frame", 0xc, "/data/app/~~abc/libengine.so", "524acc06c9af"))
                        message(4, frame(0x10458, 0x7a1234510458, null, 0, "/data/app/~~abc/libengine.so", "524acc06c9af"))
                    }
                }
                .message(16) {
                    // threads[1] — another thread that must be ignored
                    varint(1, 1)
                    message(2) {
                        varint(1, 1)
                        message(4, frame(0x1, 0x1, "other_thread_frame", 0, "/system/lib64/libc.so", "ffff"))
                    }
                }
                .toByteArray()

        val parsed = TombstoneParser().parse(tombstone)

        assertEquals("arm64", parsed.arch)
        assertEquals(4343L, parsed.tid)
        assertEquals("SIGSEGV", parsed.signalName)
        assertEquals("SEGV_MAPERR", parsed.signalCodeName)
        assertEquals(0xdeadL, parsed.faultAddress)
        assertNull(parsed.abortMessage)

        assertEquals(2, parsed.frames.size)
        val crashFrame = parsed.frames[0]
        assertEquals(0x103d8L, crashFrame.relPc)
        assertEquals(0x7a12345103d8L, crashFrame.pc)
        assertEquals("process_frame", crashFrame.functionName)
        assertEquals(0xcL, crashFrame.functionOffset)
        assertEquals("/data/app/~~abc/libengine.so", crashFrame.fileName)
        assertEquals("524acc06c9af", crashFrame.buildId)
        assertNull(parsed.frames[1].functionName)
    }

    @Test
    fun `fault address is null when the signal has none`() {
        val tombstone =
            TestProtoWriter()
                .varint(6, 1)
                .message(10) {
                    string(2, "SIGABRT")
                    varint(8, 0) // has_fault_address = false
                    varint(9, 0x1234)
                }
                .string(14, "assertion failed: x != null")
                .toByteArray()

        val parsed = TombstoneParser().parse(tombstone)

        assertEquals("SIGABRT", parsed.signalName)
        assertNull(parsed.faultAddress)
        assertEquals("assertion failed: x != null", parsed.abortMessage)
        // proto3 omits the zero-valued ARM32 enum, so no arch field means arm
        assertEquals("arm", parsed.arch)
    }

    @Test
    fun `unknown fields and wire types are skipped`() {
        val tombstone =
            TestProtoWriter()
                .string(2, "google/panther/panther:13") // build_fingerprint, unused
                .varint(6, 7)
                .varint(22, 4096) // page_size, unused
                .message(16) {
                    varint(1, 7)
                    message(2) {
                        varint(6, -1) // tagged_addr_ctrl (unused varint)
                        message(4, frame(0x10, 0x2010, "f", 0, null, null))
                    }
                }
                .toByteArray()

        val parsed = TombstoneParser().parse(tombstone)

        assertEquals(1, parsed.frames.size)
        assertEquals("f", parsed.frames[0].functionName)
        assertNull(parsed.frames[0].fileName)
        assertNull(parsed.frames[0].buildId)
    }

    @Test
    fun `parses the process header and every thread for the crash report`() {
        val tombstone =
            TestProtoWriter()
                .varint(1, 1) // arch = ARM64
                .string(2, "google/sdk_gphone64_arm64/emu64a:15/AE3A/1:userdebug/dev-keys")
                .string(3, "0")
                .string(4, "2026-10-20 23:10:00.000000000+0000")
                .varint(5, 4242) // pid
                .varint(6, 4343) // tid
                .varint(7, 10123) // uid
                .string(9, "com.example.app")
                .varint(20, 12) // process_uptime
                .message(10) {
                    varint(1, 6)
                    string(2, "SIGABRT")
                    varint(3, -6L) // SI_TKILL, sign-extended on the wire
                    string(4, "SI_TKILL")
                    varint(5, 1) // has_sender
                    varint(6, 10123)
                    varint(7, 4242)
                    varint(8, 1) // has_fault_address; address 0 itself is omitted
                }
                .varint(22, 16384) // page_size
                .message(15) { string(1, "null pointer dereference") }
                .message(16) {
                    varint(1, 4343)
                    message(2) {
                        varint(1, 4343)
                        string(2, "RenderThread")
                        message(3) {
                            string(1, "x0")
                            varint(2, 0x1L)
                        }
                        message(3) {
                            string(1, "pc")
                            varint(2, 0x7a12345103d8L)
                        }
                        string(7, "some frames may be missing")
                        message(4) {
                            varint(1, 0x103d8)
                            string(6, "/data/app/~~abc/base.apk")
                            varint(7, 0x2000) // file_map_offset
                        }
                    }
                }
                .message(16) {
                    varint(1, 1)
                    message(2) {
                        varint(1, 1)
                        string(2, "main")
                    }
                }
                .toByteArray()

        val parsed = TombstoneParser().parse(tombstone)

        assertEquals("google/sdk_gphone64_arm64/emu64a:15/AE3A/1:userdebug/dev-keys", parsed.buildFingerprint)
        assertEquals("0", parsed.revision)
        assertEquals("2026-10-20 23:10:00.000000000+0000", parsed.timestamp)
        assertEquals(4242L, parsed.pid)
        assertEquals(10123L, parsed.uid)
        assertEquals(12L, parsed.processUptime)
        assertEquals(listOf("com.example.app"), parsed.commandLine)
        assertEquals(6, parsed.signalNumber)
        assertEquals(-6, parsed.signalCode)
        assertEquals(4242 to 10123, parsed.sender)
        assertTrue(parsed.hasFaultAddress)
        assertNull(parsed.faultAddress)
        assertEquals(16384L, parsed.pageSize)
        assertEquals(listOf("null pointer dereference"), parsed.causes)

        assertEquals(setOf(4343L, 1L), parsed.threads.keys)
        val crashed = parsed.threads.getValue(4343L)
        assertEquals("RenderThread", crashed.name)
        assertEquals(listOf("x0" to 0x1L, "pc" to 0x7a12345103d8L), crashed.registers)
        assertEquals(listOf("some frames may be missing"), crashed.backtraceNotes)
        assertEquals(0x2000L, crashed.frames.single().fileMapOffset)
        assertEquals("main", parsed.threads.getValue(1L).name)
    }

    @Test
    fun `a malformed cause or register is dropped without failing the crash`() {
        // human_readable / name claim 5 bytes but only 1 follows
        val malformed = byteArrayOf(0x0A, 0x05, 'x'.code.toByte())
        val tombstone =
            TestProtoWriter()
                .varint(6, 1) // tid
                .bytes(15, malformed) // causes
                .message(16) {
                    varint(1, 1)
                    message(2) {
                        varint(1, 1)
                        bytes(3, malformed) // registers
                        message(4, frame(0x10, 0x10, null, 0, "/system/lib64/libc.so", null))
                    }
                }
                .toByteArray()

        val parsed = TombstoneParser().parse(tombstone)

        assertEquals(1, parsed.frames.size)
        assertTrue(parsed.causes.isEmpty())
        assertTrue(parsed.threads.getValue(1L).registers.isEmpty())
    }
}

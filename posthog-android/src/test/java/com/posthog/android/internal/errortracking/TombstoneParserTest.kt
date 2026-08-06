package com.posthog.android.internal.errortracking

import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

        val parsed = TombstoneParser().parse(ByteArrayInputStream(tombstone))

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

        val parsed = TombstoneParser().parse(ByteArrayInputStream(tombstone))

        assertEquals("SIGABRT", parsed.signalName)
        assertNull(parsed.faultAddress)
        assertEquals("assertion failed: x != null", parsed.abortMessage)
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

        val parsed = TombstoneParser().parse(ByteArrayInputStream(tombstone))

        assertEquals(1, parsed.frames.size)
        assertEquals("f", parsed.frames[0].functionName)
        assertNull(parsed.frames[0].fileName)
        assertNull(parsed.frames[0].buildId)
    }
}

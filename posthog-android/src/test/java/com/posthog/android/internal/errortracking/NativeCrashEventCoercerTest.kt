package com.posthog.android.internal.errortracking

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class NativeCrashEventCoercerTest {
    private val coercer = NativeCrashEventCoercer(inAppPathPrefixes = listOf("/data/app/~~abc"))

    private fun tombstone(
        frames: List<NativeCrashFrame>,
        abortMessage: String? = null,
    ): NativeCrashTombstone =
        NativeCrashTombstone(
            arch = "arm64",
            tid = 4343L,
            signalName = "SIGSEGV",
            signalCodeName = "SEGV_MAPERR",
            faultAddress = 0xdeadL,
            abortMessage = abortMessage,
            frames = frames,
        )

    @Suppress("UNCHECKED_CAST")
    private fun exception(properties: Map<String, Any>): Map<String, Any> =
        (properties["\$exception_list"] as List<Map<String, Any>>).single()

    @Suppress("UNCHECKED_CAST")
    private fun frames(properties: Map<String, Any>): List<Map<String, Any>> =
        (exception(properties)["stacktrace"] as Map<String, Any>)["frames"] as List<Map<String, Any>>

    @Test
    fun `emits native frames bottom-up with tombstone-derived addresses`() {
        val properties =
            coercer.toPostHogProperties(
                tombstone(
                    frames =
                        listOf(
                            // crash site first, as tombstones report it
                            NativeCrashFrame(
                                relPc = 0x103d8,
                                pc = 0x7a12345103d8,
                                functionName = "process_frame",
                                functionOffset = 0xc,
                                fileName = "/data/app/~~abc/libengine.so",
                                buildId = "5c6893c3dc6e76d2cbd637e4c8b4e2aaf90088b3",
                            ),
                            NativeCrashFrame(
                                relPc = 0x8501c,
                                pc = 0x7a123468501c,
                                functionName = "__start_thread",
                                functionOffset = 0x40,
                                fileName = "/apex/com.android.runtime/lib64/bionic/libc.so",
                                buildId = "aabbccdd",
                            ),
                        ),
                ),
            )

        val frames = frames(properties)
        assertEquals(2, frames.size)

        // Wire order is canonical bottom-up: outermost first, crash site last
        val outer = frames[0]
        assertEquals("native", outer["platform"])
        assertEquals("0x7a123468501d", outer["instruction_addr"])
        assertEquals("0x7a1234600000", outer["image_addr"])
        assertEquals("0x7a1234684fdc", outer["symbol_addr"])
        assertEquals("__start_thread", outer["function"])
        assertEquals(true, outer["client_resolved"])
        assertEquals("libc.so", outer["module"])
        assertEquals(false, outer["in_app"])

        val crash = frames[1]
        assertEquals("0x7a12345103d9", crash["instruction_addr"])
        assertEquals("0x7a1234500000", crash["image_addr"])
        assertEquals("libengine.so", crash["module"])
        assertEquals(true, crash["in_app"])

        val exception = exception(properties)
        assertEquals("SIGSEGV", exception["type"])
        assertEquals("SEGV_MAPERR at 0xdead", exception["value"])
        assertEquals(4343L, exception["thread_id"])
        assertEquals(
            mapOf("handled" to false, "synthetic" to false, "type" to "signal"),
            exception["mechanism"],
        )
        assertEquals("fatal", properties["\$exception_level"])
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `debug images are deduplicated per module and carry the derived debug id`() {
        val buildId = "5c6893c3dc6e76d2cbd637e4c8b4e2aaf90088b3"
        val properties =
            coercer.toPostHogProperties(
                tombstone(
                    frames =
                        listOf(
                            NativeCrashFrame(0x103d8, 0x7a12345103d8, null, 0, "/data/app/~~abc/libengine.so", buildId),
                            NativeCrashFrame(0x10458, 0x7a1234510458, null, 0, "/data/app/~~abc/libengine.so", buildId),
                            NativeCrashFrame(0x100, 0x100, null, 0, null, null),
                        ),
                ),
            )

        val images = properties["\$debug_images"] as List<Map<String, Any>>
        assertEquals(1, images.size)
        val image = images.single()
        // The same derivation the upload side applies to the ELF, pinned by
        // cymbal's android fixture (libtest_android.so)
        assertEquals("c393685c-6edc-d276-cbd6-37e4c8b4e2aa", image["debug_id"])
        assertEquals(buildId, image["code_id"])
        assertEquals("0x7a1234500000", image["image_addr"])
        assertEquals("elf", image["type"])
        assertEquals("/data/app/~~abc/libengine.so", image["code_file"])
        assertEquals("arm64", image["arch"])

        // Frames without a function are not client-resolved and carry no symbol
        val frame = frames(properties)[0]
        assertNull(frame["function"])
        assertNull(frame["client_resolved"])
        assertNull(frame["symbol_addr"])
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `the same library mapped at two bases gets one image per base`() {
        val buildId = "5c6893c3dc6e76d2cbd637e4c8b4e2aaf90088b3"
        val properties =
            coercer.toPostHogProperties(
                tombstone(
                    frames =
                        listOf(
                            NativeCrashFrame(0x1000, 0x7a0000001000, null, 0, "/data/app/~~abc/libengine.so", buildId),
                            NativeCrashFrame(0x1000, 0x7b0000001000, null, 0, "/data/app/~~abc/libengine.so", buildId),
                        ),
                ),
            )

        val images = properties["\$debug_images"] as List<Map<String, Any>>
        // frames from a base without its own image entry would not symbolicate
        assertEquals(2, images.size)
        assertEquals(
            setOf("0x7a0000000000", "0x7b0000000000"),
            images.map { it["image_addr"] }.toSet(),
        )
        assertEquals(setOf("c393685c-6edc-d276-cbd6-37e4c8b4e2aa"), images.map { it["debug_id"] }.toSet())
    }

    @Test
    fun `abort message wins over the signal description`() {
        val properties =
            coercer.toPostHogProperties(
                tombstone(frames = emptyList(), abortMessage = "FORTIFY: fdsan double-close"),
            )

        val exception = exception(properties)
        assertEquals("FORTIFY: fdsan double-close", exception["value"])
        assertNull(exception["stacktrace"])
        assertNull(properties["\$debug_images"])
    }

    @Test
    fun `debug id derivation matches the symbolic vocabulary`() {
        // 20-byte GNU build id: first 16 bytes read as a little-endian GUID
        assertEquals(
            "c393685c-6edc-d276-cbd6-37e4c8b4e2aa",
            NativeCrashEventCoercer.debugIdFromBuildId("5c6893c3dc6e76d2cbd637e4c8b4e2aaf90088b3"),
        )
        // 8-byte fast build id: zero-padded to 16 bytes
        assertEquals(
            "c393685c-6edc-d276-0000-000000000000",
            NativeCrashEventCoercer.debugIdFromBuildId("5c6893c3dc6e76d2"),
        )
        assertNull(NativeCrashEventCoercer.debugIdFromBuildId(""))
        assertNull(NativeCrashEventCoercer.debugIdFromBuildId("zz"))
        assertNull(NativeCrashEventCoercer.debugIdFromBuildId("abc"))
    }
}

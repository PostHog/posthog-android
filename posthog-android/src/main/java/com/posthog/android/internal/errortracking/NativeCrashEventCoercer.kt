package com.posthog.android.internal.errortracking

/**
 * Converts a parsed tombstone into `$exception` event properties using the
 * native stack frame contract: frames carry raw instruction addresses and the
 * event carries `$debug_images` entries keyed by debug id, so the server can
 * symbolicate against uploaded `.so` debug symbols.
 */
internal class NativeCrashEventCoercer {
    fun toPostHogProperties(tombstone: NativeCrashTombstone): MutableMap<String, Any> {
        val frames = mutableListOf<Map<String, Any>>()
        // debug id -> image entry; one image per mapped ELF, shared by its frames
        val debugImages = LinkedHashMap<String, Map<String, Any>>()

        // Tombstones list the crash site first; the wire order is canonical
        // bottom-up (outermost first, crash site last).
        tombstone.frames.reversed().forEach { frame ->
            val imageAddr = frame.pc - frame.relPc

            val myFrame = mutableMapOf<String, Any>()
            myFrame["platform"] = "native"
            myFrame["instruction_addr"] = hex(frame.pc)
            myFrame["image_addr"] = hex(imageAddr)
            myFrame["in_app"] = isInApp(frame.fileName)
            myFrame["synthetic"] = false

            frame.functionName?.let { name ->
                myFrame["function"] = name
                myFrame["client_resolved"] = true
                // The tombstone's function_offset is pc-relative, so the
                // enclosing symbol starts at pc - offset.
                myFrame["symbol_addr"] = hex(frame.pc - frame.functionOffset)
            }
            frame.fileName?.let { path ->
                myFrame["module"] = path.substringAfterLast('/')
            }

            frames.add(myFrame)

            val buildId = frame.buildId ?: return@forEach
            val debugId = debugIdFromBuildId(buildId) ?: return@forEach
            debugImages.getOrPut(debugId) {
                val image = mutableMapOf<String, Any>()
                image["debug_id"] = debugId
                image["code_id"] = buildId
                image["image_addr"] = hex(imageAddr)
                image["type"] = "elf"
                frame.fileName?.let { image["code_file"] = it }
                tombstone.arch?.let { image["arch"] = it }
                image
            }
        }

        val exception = mutableMapOf<String, Any>()
        exception["type"] = tombstone.signalName ?: "NativeCrash"
        exception["value"] = exceptionValue(tombstone)
        exception["mechanism"] =
            mapOf(
                "handled" to false,
                "synthetic" to false,
                "type" to "signal",
            )
        tombstone.tid?.let { exception["thread_id"] = it }
        if (frames.isNotEmpty()) {
            exception["stacktrace"] =
                mapOf(
                    "type" to "raw",
                    "frames" to frames,
                )
        }

        val properties = mutableMapOf<String, Any>()
        properties["\$exception_list"] = listOf(exception)
        properties["\$exception_level"] = "fatal"
        if (debugImages.isNotEmpty()) {
            properties["\$debug_images"] = debugImages.values.toList()
        }
        return properties
    }

    private fun exceptionValue(tombstone: NativeCrashTombstone): String {
        tombstone.abortMessage?.let { return it }
        val code = tombstone.signalCodeName
        val fault = tombstone.faultAddress
        return when {
            code != null && fault != null -> "$code at ${hex(fault)}"
            code != null -> code
            else -> "Native crash"
        }
    }

    // App code lives under /data (installed APKs and extracted libs); anything
    // else (/system, /apex, /vendor) is OS-owned. Unknown mappings (JIT,
    // anonymous) stay out-of-app.
    private fun isInApp(fileName: String?): Boolean = fileName?.startsWith("/data/") == true

    private fun hex(value: Long): String = "0x${java.lang.Long.toUnsignedString(value, 16)}"

    internal companion object {
        /**
         * Derives the debug id matching an uploaded symbol set from a GNU
         * build id hex string: the first 16 bytes (zero-padded) read as a
         * little-endian GUID, i.e. the first three fields byte-swapped. This
         * mirrors how the upload side derives the chunk id from the ELF.
         */
        internal fun debugIdFromBuildId(buildId: String): String? {
            if (buildId.length < 2 || buildId.length % 2 != 0) {
                return null
            }
            val bytes = ByteArray(16)
            val available = minOf(buildId.length / 2, 16)
            for (i in 0 until available) {
                val byte = buildId.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null
                bytes[i] = byte.toByte()
            }

            fun byte(i: Int): String = String.format("%02x", bytes[i])
            val d1 = byte(3) + byte(2) + byte(1) + byte(0)
            val d2 = byte(5) + byte(4)
            val d3 = byte(7) + byte(6)
            val d4 = byte(8) + byte(9)
            val d5 = (10..15).joinToString("") { byte(it) }
            return "$d1-$d2-$d3-$d4-$d5"
        }
    }
}

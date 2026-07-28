package com.posthog.android.internal.errortracking

import java.io.IOException
import java.io.InputStream

/**
 * A native crash extracted from a debuggerd tombstone.
 *
 * Frames are in tombstone order: frame 0 is the crash site, the last frame is
 * the outermost caller.
 */
internal data class NativeCrashTombstone(
    val arch: String?,
    val tid: Long?,
    val signalName: String?,
    val signalCodeName: String?,
    val faultAddress: Long?,
    val abortMessage: String?,
    val frames: List<NativeCrashFrame>,
)

internal data class NativeCrashFrame(
    /** Program counter relative to the ELF the frame is in. */
    val relPc: Long,
    /** Absolute program counter at crash time. */
    val pc: Long,
    val functionName: String?,
    val functionOffset: Long,
    /** Path of the mapped ELF (e.g. /data/app/.../libfoo.so). */
    val fileName: String?,
    /** GNU build id of the mapped ELF as a hex string, when known. */
    val buildId: String?,
)

/**
 * Minimal protobuf wire-format reader for debuggerd's tombstone proto
 * (AOSP system/core/debuggerd/proto/tombstone.proto), extracting only what
 * error tracking needs: the crashing thread's backtrace, signal info, and the
 * abort message. Unknown fields are skipped, so schema additions are ignored.
 *
 * Hand-rolled to avoid shipping a protobuf runtime in the SDK; the field
 * numbers used here are frozen in AOSP.
 */
internal class TombstoneParser {
    @Throws(IOException::class)
    fun parse(stream: InputStream): NativeCrashTombstone {
        val reader = ProtoReader(stream.readBytes())

        var arch: String? = null
        var tid: Long? = null
        var signalName: String? = null
        var signalCodeName: String? = null
        var faultAddress: Long? = null
        var hasFaultAddress = false
        var abortMessage: String? = null
        // tid can serialize after the threads map, so collect every thread's
        // backtrace and pick the crashing one at the end.
        val backtraces = mutableMapOf<Long, List<NativeCrashFrame>>()

        while (reader.hasMore()) {
            when (reader.readTag()) {
                // Architecture arch = 1
                fieldVarint(1) -> arch = archName(reader.readVarint())
                // uint32 tid = 6
                fieldVarint(6) -> tid = reader.readVarint()
                // Signal signal_info = 10
                fieldBytes(10) -> {
                    val signal = ProtoReader(reader.readBytes())
                    while (signal.hasMore()) {
                        when (signal.readTag()) {
                            // string name = 2
                            fieldBytes(2) -> signalName = signal.readString()
                            // string code_name = 4
                            fieldBytes(4) -> signalCodeName = signal.readString()
                            // bool has_fault_address = 8
                            fieldVarint(8) -> hasFaultAddress = signal.readVarint() != 0L
                            // uint64 fault_address = 9
                            fieldVarint(9) -> faultAddress = signal.readVarint()
                            else -> signal.skipLast()
                        }
                    }
                }
                // string abort_message = 14
                fieldBytes(14) -> abortMessage = reader.readString().ifEmpty { null }
                // map<uint32, Thread> threads = 16
                fieldBytes(16) -> {
                    val entry = ProtoReader(reader.readBytes())
                    var key: Long? = null
                    var frames: List<NativeCrashFrame>? = null
                    while (entry.hasMore()) {
                        when (entry.readTag()) {
                            fieldVarint(1) -> key = entry.readVarint()
                            fieldBytes(2) -> frames = parseThreadBacktrace(entry.readBytes())
                            else -> entry.skipLast()
                        }
                    }
                    key?.let { backtraces[it] = frames ?: emptyList() }
                }
                else -> reader.skipLast()
            }
        }

        return NativeCrashTombstone(
            arch = arch,
            tid = tid,
            signalName = signalName,
            signalCodeName = signalCodeName,
            faultAddress = if (hasFaultAddress) faultAddress else null,
            abortMessage = abortMessage,
            frames = tid?.let { backtraces[it] } ?: emptyList(),
        )
    }

    private fun parseThreadBacktrace(bytes: ByteArray): List<NativeCrashFrame> {
        val thread = ProtoReader(bytes)
        val frames = mutableListOf<NativeCrashFrame>()
        while (thread.hasMore()) {
            when (thread.readTag()) {
                // repeated BacktraceFrame current_backtrace = 4
                fieldBytes(4) -> {
                    val frame = ProtoReader(thread.readBytes())
                    var relPc = 0L
                    var pc = 0L
                    var functionName: String? = null
                    var functionOffset = 0L
                    var fileName: String? = null
                    var buildId: String? = null
                    while (frame.hasMore()) {
                        when (frame.readTag()) {
                            fieldVarint(1) -> relPc = frame.readVarint()
                            fieldVarint(2) -> pc = frame.readVarint()
                            fieldBytes(4) -> functionName = frame.readString().ifEmpty { null }
                            fieldVarint(5) -> functionOffset = frame.readVarint()
                            fieldBytes(6) -> fileName = frame.readString().ifEmpty { null }
                            fieldBytes(8) -> buildId = frame.readString().ifEmpty { null }
                            else -> frame.skipLast()
                        }
                    }
                    frames.add(NativeCrashFrame(relPc, pc, functionName, functionOffset, fileName, buildId))
                }
                else -> thread.skipLast()
            }
        }
        return frames
    }

    private fun archName(value: Long): String? =
        when (value) {
            0L -> "arm"
            1L -> "arm64"
            2L -> "x86"
            3L -> "x86_64"
            4L -> "riscv64"
            else -> null
        }

    private fun fieldVarint(field: Int): Int = field shl 3

    private fun fieldBytes(field: Int): Int = (field shl 3) or 2
}

/** Cursor over protobuf wire data: tags, varints, and length-delimited chunks. */
private class ProtoReader(private val data: ByteArray) {
    private var pos = 0
    private var lastTag = 0

    fun hasMore(): Boolean = pos < data.size

    fun readTag(): Int {
        lastTag = readVarint().toInt()
        return lastTag
    }

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (pos >= data.size || shift > 63) {
                throw IOException("malformed varint at $pos")
            }
            val b = data[pos++].toInt()
            result = result or ((b.toLong() and 0x7f) shl shift)
            if (b and 0x80 == 0) {
                return result
            }
            shift += 7
        }
    }

    fun readBytes(): ByteArray {
        val length = readVarint().toInt()
        if (length < 0 || pos + length > data.size) {
            throw IOException("malformed length $length at $pos")
        }
        val bytes = data.copyOfRange(pos, pos + length)
        pos += length
        return bytes
    }

    fun readString(): String = String(readBytes(), Charsets.UTF_8)

    /** Skips the value of the tag returned by the last [readTag] call. */
    fun skipLast() {
        when (lastTag and 0x7) {
            0 -> readVarint()
            1 -> advance(8)
            2 -> readBytes()
            5 -> advance(4)
            else -> throw IOException("unsupported wire type ${lastTag and 0x7} at $pos")
        }
    }

    private fun advance(count: Int) {
        if (pos + count > data.size) {
            throw IOException("truncated fixed field at $pos")
        }
        pos += count
    }
}

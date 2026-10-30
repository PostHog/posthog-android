package com.posthog.android.internal.errortracking

import java.io.IOException

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
    val buildFingerprint: String? = null,
    val revision: String? = null,
    val timestamp: String? = null,
    val pid: Long? = null,
    val uid: Long? = null,
    val processUptime: Long? = null,
    val pageSize: Long? = null,
    val commandLine: List<String> = emptyList(),
    val signalNumber: Int? = null,
    val signalCode: Int? = null,
    /**
     * Whether the signal carries a fault address. [faultAddress] is null for
     * address 0, because proto3 leaves zero values off the wire.
     */
    val hasFaultAddress: Boolean = false,
    /** pid and uid of the process that sent the signal, when another process sent it. */
    val sender: Pair<Int, Int>? = null,
    val causes: List<String> = emptyList(),
    /** Every thread in the tombstone, keyed by thread id. */
    val threads: Map<Long, NativeCrashThread> = emptyMap(),
)

internal data class NativeCrashThread(
    val id: Long,
    val name: String?,
    /** Register names and values, in tombstone order. */
    val registers: List<Pair<String, Long>>,
    val backtraceNotes: List<String>,
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
    /** Offset of the mapping within the file, non-zero for libraries loaded straight from an APK. */
    val fileMapOffset: Long = 0,
)

/**
 * Minimal protobuf wire-format reader for debuggerd's tombstone proto
 * (AOSP system/core/debuggerd/proto/tombstone.proto), extracting only what
 * error tracking needs: the process header, signal info, the abort message,
 * and every thread's registers and backtrace. Unknown fields are skipped, so
 * schema additions are ignored.
 *
 * Hand-rolled to avoid shipping a protobuf runtime in the SDK; the field
 * numbers used here are frozen in AOSP.
 */
internal class TombstoneParser {
    @Throws(IOException::class)
    fun parse(tombstone: ByteArray): NativeCrashTombstone {
        val reader = ProtoReader(tombstone)

        // proto3 omits zero-valued enums, and ARM32 is enum value zero, so an
        // absent arch field means a 32-bit ARM tombstone.
        var arch: String? = "arm"
        var tid: Long? = null
        var signalName: String? = null
        var signalCodeName: String? = null
        var faultAddress: Long? = null
        var hasFaultAddress = false
        var abortMessage: String? = null
        var buildFingerprint: String? = null
        var revision: String? = null
        var timestamp: String? = null
        var pid: Long? = null
        var uid: Long? = null
        var processUptime: Long? = null
        var pageSize: Long? = null
        val commandLine = mutableListOf<String>()
        var signalNumber: Int? = null
        var signalCode: Int? = null
        var hasSender = false
        var senderUid = 0
        var senderPid = 0
        val causes = mutableListOf<String>()
        // tid can serialize after the threads map, so collect every thread and
        // pick the crashing one at the end.
        val threads = mutableMapOf<Long, NativeCrashThread>()

        while (reader.hasMore()) {
            when (reader.readTag()) {
                // Architecture arch = 1
                fieldVarint(1) -> arch = archName(reader.readVarint())
                // string build_fingerprint = 2
                fieldBytes(2) -> buildFingerprint = reader.readString()
                // string revision = 3
                fieldBytes(3) -> revision = reader.readString()
                // string timestamp = 4
                fieldBytes(4) -> timestamp = reader.readString()
                // uint32 pid = 5
                fieldVarint(5) -> pid = reader.readVarint()
                // uint32 tid = 6
                fieldVarint(6) -> tid = reader.readVarint()
                // uint32 uid = 7
                fieldVarint(7) -> uid = reader.readVarint()
                // repeated string command_line = 9
                fieldBytes(9) -> commandLine.add(reader.readString())
                // uint32 process_uptime = 20
                fieldVarint(20) -> processUptime = reader.readVarint()
                // uint32 page_size = 22
                fieldVarint(22) -> pageSize = reader.readVarint()
                // repeated Cause causes = 15
                fieldBytes(15) -> {
                    val bytes = reader.readBytes()
                    // Only the report uses causes, so a malformed one is dropped
                    // rather than failing the whole crash.
                    runCatching { parseCause(bytes) }.getOrNull()?.let(causes::add)
                }
                // Signal signal_info = 10
                fieldBytes(10) -> {
                    val signal = ProtoReader(reader.readBytes())
                    while (signal.hasMore()) {
                        when (signal.readTag()) {
                            // int32 number = 1
                            fieldVarint(1) -> signalNumber = signal.readVarint().toInt()
                            // string name = 2
                            fieldBytes(2) -> signalName = signal.readString()
                            // int32 code = 3; negative codes (e.g. SI_TKILL) arrive sign-extended
                            fieldVarint(3) -> signalCode = signal.readVarint().toInt()
                            // string code_name = 4
                            fieldBytes(4) -> signalCodeName = signal.readString()
                            // bool has_sender = 5
                            fieldVarint(5) -> hasSender = signal.readVarint() != 0L
                            // int32 sender_uid = 6
                            fieldVarint(6) -> senderUid = signal.readVarint().toInt()
                            // int32 sender_pid = 7
                            fieldVarint(7) -> senderPid = signal.readVarint().toInt()
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
                    var thread: NativeCrashThread? = null
                    while (entry.hasMore()) {
                        when (entry.readTag()) {
                            fieldVarint(1) -> key = entry.readVarint()
                            fieldBytes(2) -> thread = parseThread(entry.readBytes())
                            else -> entry.skipLast()
                        }
                    }
                    key?.let { threads[it] = thread ?: NativeCrashThread(it, null, emptyList(), emptyList(), emptyList()) }
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
            frames = tid?.let { threads[it]?.frames } ?: emptyList(),
            buildFingerprint = buildFingerprint,
            revision = revision,
            timestamp = timestamp,
            pid = pid,
            uid = uid,
            processUptime = processUptime,
            pageSize = pageSize,
            commandLine = commandLine,
            signalNumber = signalNumber,
            signalCode = signalCode,
            hasFaultAddress = hasFaultAddress,
            sender = if (hasSender) senderPid to senderUid else null,
            causes = causes,
            threads = threads,
        )
    }

    private fun parseThread(bytes: ByteArray): NativeCrashThread {
        val thread = ProtoReader(bytes)
        var id = 0L
        var name: String? = null
        val registers = mutableListOf<Pair<String, Long>>()
        val backtraceNotes = mutableListOf<String>()
        val frames = mutableListOf<NativeCrashFrame>()
        while (thread.hasMore()) {
            when (thread.readTag()) {
                // int32 id = 1
                fieldVarint(1) -> id = thread.readVarint()
                // string name = 2
                fieldBytes(2) -> name = thread.readString().ifEmpty { null }
                // repeated Register registers = 3
                fieldBytes(3) -> {
                    val bytes = thread.readBytes()
                    // Only the report uses registers, so a malformed one is dropped
                    // rather than failing the whole crash.
                    runCatching { parseRegister(bytes) }.getOrNull()?.let(registers::add)
                }
                // repeated string backtrace_note = 7
                fieldBytes(7) -> backtraceNotes.add(thread.readString())
                // repeated BacktraceFrame current_backtrace = 4
                fieldBytes(4) -> {
                    val frame = ProtoReader(thread.readBytes())
                    var relPc = 0L
                    var pc = 0L
                    var functionName: String? = null
                    var functionOffset = 0L
                    var fileName: String? = null
                    var buildId: String? = null
                    var fileMapOffset = 0L
                    while (frame.hasMore()) {
                        when (frame.readTag()) {
                            fieldVarint(1) -> relPc = frame.readVarint()
                            fieldVarint(2) -> pc = frame.readVarint()
                            fieldBytes(4) -> functionName = frame.readString().ifEmpty { null }
                            fieldVarint(5) -> functionOffset = frame.readVarint()
                            fieldBytes(6) -> fileName = frame.readString().ifEmpty { null }
                            fieldVarint(7) -> fileMapOffset = frame.readVarint()
                            fieldBytes(8) -> buildId = frame.readString().ifEmpty { null }
                            else -> frame.skipLast()
                        }
                    }
                    frames.add(NativeCrashFrame(relPc, pc, functionName, functionOffset, fileName, buildId, fileMapOffset))
                }
                else -> thread.skipLast()
            }
        }
        return NativeCrashThread(id, name, registers, backtraceNotes, frames)
    }

    private fun parseCause(bytes: ByteArray): String? {
        val cause = ProtoReader(bytes)
        var humanReadable: String? = null
        while (cause.hasMore()) {
            when (cause.readTag()) {
                // string human_readable = 1
                fieldBytes(1) -> humanReadable = cause.readString()
                else -> cause.skipLast()
            }
        }
        return humanReadable
    }

    private fun parseRegister(bytes: ByteArray): Pair<String, Long> {
        val register = ProtoReader(bytes)
        var name = ""
        var value = 0L
        while (register.hasMore()) {
            when (register.readTag()) {
                // string name = 1
                fieldBytes(1) -> name = register.readString()
                // uint64 u64 = 2
                fieldVarint(2) -> value = register.readVarint()
                else -> register.skipLast()
            }
        }
        return name to value
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

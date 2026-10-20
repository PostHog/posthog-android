package com.posthog.android.internal.errortracking

/**
 * Renders a parsed tombstone as the text debuggerd writes to logcat and
 * /data/tombstones, following AOSP's tombstone_proto_to_text.cpp (main, October 2026): the process
 * header, signal, abort message, the crashing thread's registers, and every
 * thread's backtrace.
 *
 * Memory dumps, memory maps, open files, and log buffers are left out: they
 * can hold user data, and the maps alone run to thousands of lines. Other
 * threads' registers are left out too, as in debuggerd's logcat output: they
 * add about a sixth to the text and are rarely needed.
 */
internal object NativeCrashReportFormatter {
    /** Returns null when the tombstone has no entry for the crashing thread, like debuggerd. */
    fun format(tombstone: NativeCrashTombstone): String? {
        val crashedThread = tombstone.tid?.let { tombstone.threads[it] } ?: return null
        val wordSize = pointerWidth(tombstone.arch)
        val lines = mutableListOf<String>()

        lines += "*** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***"
        lines += "Build fingerprint: '${tombstone.buildFingerprint.orEmpty()}'"
        lines += "Revision: '${tombstone.revision.orEmpty()}'"
        lines += "ABI: '${tombstone.arch ?: "<unknown>"}'"
        lines += "Timestamp: ${tombstone.timestamp.orEmpty()}"
        lines += "Process uptime: ${tombstone.processUptime ?: 0}s"
        tombstone.pageSize?.takeIf { it != 4096L }?.let { lines += "Page size: $it bytes" }

        lines += threadHeader(tombstone, crashedThread)
        if (tombstone.signalNumber == null && tombstone.signalName == null) {
            lines += "signal information missing"
        } else {
            val sender = tombstone.sender?.let { (pid, uid) -> " from pid $pid, uid $uid" }.orEmpty()
            val faultAddress =
                if (tombstone.hasFaultAddress) "0x${hex(tombstone.faultAddress ?: 0, 2 * wordSize)}" else "--------"
            lines += "signal ${tombstone.signalNumber ?: 0} (${tombstone.signalName.orEmpty()})," +
                " code ${tombstone.signalCode ?: 0} (${tombstone.signalCodeName.orEmpty()}$sender), fault addr $faultAddress"
        }
        if (tombstone.causes.size == 1) {
            lines += "Cause: ${tombstone.causes[0]}"
        }
        tombstone.abortMessage?.let { lines += "Abort message: '$it'" }
        lines += registers(tombstone.arch, crashedThread, wordSize)
        lines += backtrace(crashedThread, wordSize)
        if (tombstone.causes.size > 1) {
            lines += ""
            lines += "Note: multiple potential causes for this crash were detected, listing them in decreasing order of likelihood."
            tombstone.causes.forEach { lines += listOf("", "Cause: $it") }
        }

        // A map has no order, so list the other threads by id, as debuggerd does.
        tombstone.threads.keys.filter { it != tombstone.tid }.sorted().forEach { tid ->
            val thread = tombstone.threads.getValue(tid)
            lines += "--- --- --- --- --- --- --- --- --- --- --- --- --- --- --- ---"
            lines += threadHeader(tombstone, thread)
            lines += backtrace(thread, wordSize)
        }

        return lines.joinToString("\n", postfix = "\n")
    }

    private fun threadHeader(
        tombstone: NativeCrashTombstone,
        thread: NativeCrashThread,
    ): List<String> {
        val processName = tombstone.commandLine.firstOrNull() ?: "<unknown>"
        val commandLine = if (tombstone.commandLine.isEmpty()) "<unknown>" else tombstone.commandLine.joinToString(" ")
        return listOf(
            "Cmdline: $commandLine",
            "pid: ${tombstone.pid ?: 0}, tid: ${thread.id}, name: ${thread.name.orEmpty()}  >>> $processName <<<",
            "uid: ${tombstone.uid ?: 0}",
        )
    }

    // Four general registers per row, then the special ones (pc, sp, ...) on a final row.
    private fun registers(
        arch: String?,
        thread: NativeCrashThread,
        wordSize: Int,
    ): List<String> {
        val special = specialRegisters(arch) ?: return emptyList()
        val (specialRegisters, generalRegisters) = thread.registers.partition { it.first in special }
        return (generalRegisters.chunked(4) + listOf(specialRegisters)).map { row ->
            "  " + row.joinToString("") { (name, value) -> "  ${name.padEnd(3)} ${hex(value, 2 * wordSize)}" }
        }
    }

    private fun backtrace(
        thread: NativeCrashThread,
        wordSize: Int,
    ): List<String> {
        val lines = mutableListOf("", "${thread.frames.size} total frames", "backtrace:")
        if (thread.backtraceNotes.isNotEmpty()) {
            lines += "  NOTE: " + thread.backtraceNotes.joinToString("\n  NOTE: ")
        }
        thread.frames.forEachIndexed { index, frame ->
            val line =
                StringBuilder(
                    "      #${index.toString().padStart(2, '0')} pc ${hex(frame.relPc, 2 * wordSize)}  ${frame.fileName.orEmpty()}",
                )
            if (frame.fileMapOffset != 0L) {
                line.append(" (offset 0x${hex(frame.fileMapOffset, 0)})")
            }
            frame.functionName?.let { line.append(" ($it+${frame.functionOffset})") }
            frame.buildId?.let { line.append(" (BuildId: $it)") }
            lines += line.toString()
        }
        return lines
    }

    private fun pointerWidth(arch: String?): Int =
        when (arch) {
            "arm", "x86" -> 4
            else -> 8
        }

    private fun specialRegisters(arch: String?): Set<String>? =
        when (arch) {
            "arm", "arm64" -> setOf("ip", "lr", "sp", "pc", "pst")
            "riscv64" -> setOf("ra", "sp", "pc")
            "x86" -> setOf("ebp", "esp", "eip")
            "x86_64" -> setOf("rbp", "rsp", "rip")
            else -> null
        }

    private fun hex(
        value: Long,
        width: Int,
    ): String = java.lang.Long.toHexString(value).padStart(width, '0')
}

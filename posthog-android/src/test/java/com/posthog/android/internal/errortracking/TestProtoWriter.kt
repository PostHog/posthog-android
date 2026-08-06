package com.posthog.android.internal.errortracking

import java.io.ByteArrayOutputStream

// Minimal protobuf wire encoder, so the parser is exercised against real
// wire bytes rather than its own inverse.
internal class TestProtoWriter {
    private val out = ByteArrayOutputStream()

    fun varint(
        field: Int,
        value: Long,
    ): TestProtoWriter {
        writeVarint((field shl 3).toLong())
        writeVarint(value)
        return this
    }

    fun bytes(
        field: Int,
        value: ByteArray,
    ): TestProtoWriter {
        writeVarint(((field shl 3) or 2).toLong())
        writeVarint(value.size.toLong())
        out.write(value)
        return this
    }

    fun string(
        field: Int,
        value: String,
    ): TestProtoWriter = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun message(
        field: Int,
        block: TestProtoWriter.() -> Unit,
    ): TestProtoWriter = bytes(field, TestProtoWriter().apply(block).toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun writeVarint(value: Long) {
        var v = value
        while (true) {
            if (v and 0x7fL.inv() == 0L) {
                out.write(v.toInt())
                return
            }
            out.write(((v and 0x7f) or 0x80).toInt())
            v = v ushr 7
        }
    }
}

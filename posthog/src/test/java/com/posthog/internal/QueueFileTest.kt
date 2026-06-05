package com.posthog.internal

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class QueueFileTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    @Test
    fun `versioned queue persists header and element lengths in big endian order`() {
        val file = File(tmpDir.newFolder(), "queue-file")
        val firstPayload = ByteArray(257) { it.toByte() }
        val secondPayload = byteArrayOf(0x7f, 0x80.toByte(), 0xff.toByte())

        QueueFile.Builder(file).build().use { queue ->
            queue.add(firstPayload)
            queue.add(secondPayload)
        }

        val fileBytes = file.readBytes()
        assertEquals(bytes(0x80, 0x00, 0x00, 0x01), fileBytes.bytesAt(0..3))
        assertEquals(bytes(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10, 0x00), fileBytes.bytesAt(4..11))
        assertEquals(bytes(0x00, 0x00, 0x00, 0x02), fileBytes.bytesAt(12..15))
        assertEquals(bytes(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x20), fileBytes.bytesAt(16..23))
        assertEquals(bytes(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x25), fileBytes.bytesAt(24..31))
        assertEquals(bytes(0x00, 0x00, 0x01, 0x01), fileBytes.bytesAt(32..35))
        assertEquals(bytes(0x00, 0x00, 0x00, 0x03), fileBytes.bytesAt(293..296))

        assertQueueContents(file, false, firstPayload, secondPayload)
    }

    @Test
    fun `legacy queue persists header and element lengths in big endian order`() {
        val file = File(tmpDir.newFolder(), "queue-file")
        val firstPayload = ByteArray(257) { it.toByte() }
        val secondPayload = byteArrayOf(0x7f, 0x80.toByte(), 0xff.toByte())

        QueueFile.Builder(file).forceLegacy(true).build().use { queue ->
            queue.add(firstPayload)
            queue.add(secondPayload)
        }

        val fileBytes = file.readBytes()
        assertEquals(bytes(0x00, 0x00, 0x10, 0x00), fileBytes.bytesAt(0..3))
        assertEquals(bytes(0x00, 0x00, 0x00, 0x02), fileBytes.bytesAt(4..7))
        assertEquals(bytes(0x00, 0x00, 0x00, 0x10), fileBytes.bytesAt(8..11))
        assertEquals(bytes(0x00, 0x00, 0x01, 0x15), fileBytes.bytesAt(12..15))
        assertEquals(bytes(0x00, 0x00, 0x01, 0x01), fileBytes.bytesAt(16..19))
        assertEquals(bytes(0x00, 0x00, 0x00, 0x03), fileBytes.bytesAt(277..280))

        assertQueueContents(file, true, firstPayload, secondPayload)
    }

    private fun assertQueueContents(
        file: File,
        forceLegacy: Boolean,
        vararg expectedPayloads: ByteArray,
    ) {
        QueueFile.Builder(file).forceLegacy(forceLegacy).build().use { queue ->
            assertEquals(expectedPayloads.size, queue.size())
            expectedPayloads.forEach { expectedPayload ->
                assertEquals(expectedPayload.toList(), queue.peek()!!.toList())
                queue.remove()
            }
            assertTrue(queue.isEmpty())
        }
    }

    private fun ByteArray.bytesAt(range: IntRange): List<Byte> = sliceArray(range).toList()

    private fun bytes(vararg values: Int): List<Byte> = values.map { it.toByte() }
}

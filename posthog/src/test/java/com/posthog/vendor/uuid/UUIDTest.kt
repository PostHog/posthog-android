package com.posthog.vendor.uuid

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

internal class UUIDTest {

    @Test
    fun `do not generate same value`() {
        val sut = TimeBasedEpochGenerator.getInstance()

        val uuid1 = sut.generate()
        val uuid2 = sut.generate()
        assertNotNull(uuid1)
        assertNotNull(uuid2)
        assertNotEquals(uuid1, uuid2)
    }

    @Test
    fun `generate and parse it back with java UUID class`() {
        val sut = TimeBasedEpochGenerator.getInstance()

        val uuid = sut.generate()
        assertNotNull(uuid)

        val javaUuid = UUID.fromString(uuid.toString())

        assertEquals(uuid.toString(), javaUuid.toString())
    }
}

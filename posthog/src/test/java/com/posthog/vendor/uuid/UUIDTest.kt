package com.posthog.vendor.uuid

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

internal class UUIDTest {
    @Test
    fun `do not generate same value`() {
        val uuid1 = TimeBasedEpochGenerator.generate()
        val uuid2 = TimeBasedEpochGenerator.generate()
        assertNotNull(uuid1)
        assertNotNull(uuid2)
        assertNotEquals(uuid1, uuid2)
    }

    @Test
    fun `generate and parse it back with java UUID class`() {
        val uuid = TimeBasedEpochGenerator.generate()
        assertNotNull(uuid)

        val javaUuid = UUID.fromString(uuid.toString())

        assertEquals(uuid.toString(), javaUuid.toString())
    }
}

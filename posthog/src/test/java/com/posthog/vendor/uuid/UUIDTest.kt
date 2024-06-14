package com.posthog.vendor.uuid

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

internal class UUIDTest {
    @Test
    fun `test duplicated and sorting`() {
        val count = 10_000

        val created = ArrayList<UUID>(count)
        for (i in 0 until count) {
            created.add(TimeBasedEpochGenerator.generate())
        }

        val sortedUUID = ArrayList<UUID>(created)
        sortedUUID.sortWith(UUIDComparator())
        val unique = HashSet<UUID>(count)

        for (i in created.indices) {
            assertEquals(created[i], sortedUUID[i])
            if (!unique.add(created[i])) {
                fail("Duplicate at: $i")
            }
        }
    }

    @Test
    fun `generate and parse it back with java UUID class`() {
        val uuid = TimeBasedEpochGenerator.generate()
        assertNotNull(uuid)

        val javaUuid = UUID.fromString(uuid.toString())

        assertEquals(uuid.toString(), javaUuid.toString())
    }
}

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

    @Test
    fun `test getTimestampFromUuid can convert back to timestamp`() {
        val uuid = TimeBasedEpochGenerator.generate()
        assertNotNull(uuid)

        val timestamp = TimeBasedEpochGenerator.getTimestampFromUuid(uuid)
        assertNotNull(timestamp)
    }

    @Test
    fun `test that a known UUID from posthog-js has the expected timestamp`() {
        val uuid = UUID.fromString("0196a5a9-1a29-7eaf-8f1d-81d156d4819e")
        val timestamp = TimeBasedEpochGenerator.getTimestampFromUuid(uuid)
        assertEquals(1746536045097, timestamp)
    }

    @Test
    fun `test that a known UUID from posthog-android has the expected timestamp`() {
        val uuid = UUID.fromString("0196a5d6-ec0e-792c-a483-4a69cd57bba8")
        val timestamp = TimeBasedEpochGenerator.getTimestampFromUuid(uuid)
        assertEquals(1746539047950, timestamp)
    }
}

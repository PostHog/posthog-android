package com.posthog.compliance

import org.junit.Test
import kotlin.test.assertEquals

class ObservationTest {
    private val batch = """{"batch":[{"uuid":"0198bd44-1234-7000-8000-000000000001"}]}""".toByteArray()

    @Test
    fun failedResponseDoesNotClaimQueueCompletion() {
        val observation = Observation()
        observation.record(batch, null, 400, 1)
        assertEquals(1, observation.pendingCount())
        assertEquals(0, observation.sentCount())
        assertEquals("HTTP 400", observation.state()["last_error"])
    }

    @Test
    fun retriesCountRepeatedWireUuidsWithoutDoubleCountingDelivery() {
        val observation = Observation()
        observation.record(batch, null, 503, 1)
        observation.record(batch, null, 200, 2)
        assertEquals(0, observation.pendingCount())
        assertEquals(1, observation.sentCount())
        assertEquals(1, observation.state()["total_events_captured"])
        assertEquals(1, observation.state()["total_retries"])
    }
}

package com.posthog.android.replay

import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import com.posthog.android.API_KEY
import com.posthog.internal.PostHogQueueInterface
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PostHogReplayBufferQueueTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    private class FakeQueue : PostHogQueueInterface {
        val events = mutableListOf<PostHogEvent>()

        override fun add(event: PostHogEvent) {
            events.add(event)
        }

        override fun flush() {
        }

        override fun start() {
        }

        override fun stop() {
        }

        override fun clear() {
            events.clear()
        }
    }

    private fun createQueue(bufferDir: File? = null): PostHogReplayBufferQueue {
        val config = PostHogConfig(API_KEY)
        val dir = bufferDir ?: File(tmpDir.newFolder(), "buffer")
        return PostHogReplayBufferQueue(config, dir)
    }

    private fun createEvent(name: String): PostHogEvent {
        return PostHogEvent(
            event = name,
            distinctId = "test-user",
            properties = mutableMapOf("name" to name),
            uuid = UUID.randomUUID(),
        )
    }

    @Test
    fun `add increases depth`() {
        val queue = createQueue()

        assertEquals(0, queue.depth)

        queue.add(createEvent("item1"))
        assertEquals(1, queue.depth)

        queue.add(createEvent("item2"))
        assertEquals(2, queue.depth)
    }

    @Test
    fun `clear resets depth to zero`() {
        val queue = createQueue()

        queue.add(createEvent("item1"))
        queue.add(createEvent("item2"))
        assertEquals(2, queue.depth)

        queue.clear()
        assertEquals(0, queue.depth)
    }

    @Test
    fun `init clears leftover buffer from previous session`() {
        val bufferDir = File(tmpDir.newFolder(), "buffer")
        bufferDir.mkdirs()

        File(bufferDir, "leftover.event").writeText("old data")
        assertTrue(bufferDir.listFiles()!!.isNotEmpty())

        val queue = createQueue(bufferDir)
        assertEquals(0, queue.depth)
        assertTrue(bufferDir.exists())
        assertTrue(bufferDir.listFiles()!!.isEmpty())
    }

    @Test
    fun `bufferDurationMs returns null for empty queue`() {
        val queue = createQueue()
        assertNull(queue.bufferDurationMs)
    }

    @Test
    fun `bufferDurationMs returns zero for single item`() {
        val queue = createQueue()
        queue.add(createEvent("only"))
        assertEquals(0L, queue.bufferDurationMs)
    }

    @Test
    fun `bufferDurationMs increases as items are added over time`() {
        val queue = createQueue()

        queue.add(createEvent("first"))
        val duration1 = queue.bufferDurationMs ?: 0

        Thread.sleep(50)
        queue.add(createEvent("second"))
        val duration2 = queue.bufferDurationMs ?: 0

        assertTrue(duration2 > duration1)
    }

    @Test
    fun `migrateAllTo noops when target queue is not PostHogQueue`() {
        val queue = createQueue()
        val targetQueue = FakeQueue()

        queue.add(createEvent("item1"))
        val migrated = queue.migrateAllTo(targetQueue)

        assertEquals(0, migrated)
        assertEquals(1, queue.depth)
        assertEquals(0, targetQueue.events.size)
    }

    @Test
    fun `migrateAllTo noops for non PostHogQueue target when empty`() {
        val queue = createQueue()
        val targetQueue = FakeQueue()

        val migrated = queue.migrateAllTo(targetQueue)

        assertEquals(0, migrated)
        assertEquals(0, queue.depth)
        assertEquals(0, targetQueue.events.size)
    }

    @Test
    fun `timestampFromUUIDv7 extracts correct milliseconds`() {
        val filename = "019711e4-7c00-7000-8000-000000000000.event"
        val ts = PostHogReplayBufferQueue.timestampFromUUIDv7(filename)
        assertNotNull(ts)
        assertEquals(1748351876096L, ts)
    }

    @Test
    fun `timestampFromUUIDv7 returns null for invalid filename`() {
        assertNull(PostHogReplayBufferQueue.timestampFromUUIDv7("not-a-uuid.event"))
        assertNull(PostHogReplayBufferQueue.timestampFromUUIDv7(""))
    }
}

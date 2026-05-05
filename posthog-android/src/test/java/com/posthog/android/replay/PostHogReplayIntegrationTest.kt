package com.posthog.android.replay

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.PostHogEvent
import com.posthog.PostHogInterface
import com.posthog.android.API_KEY
import com.posthog.android.PostHogAndroidConfig
import com.posthog.android.internal.MainHandler
import com.posthog.internal.PostHogLogger
import com.posthog.internal.PostHogQueueInterface
import com.posthog.internal.PostHogRemoteConfig
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [26])
internal class PostHogReplayIntegrationTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    private class FakeQueue : PostHogQueueInterface {
        override fun add(event: PostHogEvent) {
        }

        override fun flush() {
        }

        override fun start() {
        }

        override fun stop() {
        }

        override fun clear() {
        }
    }

    private class NoOpBufferDelegate : PostHogReplayBufferDelegate {
        override val isBuffering: Boolean = true

        override fun onReplayBufferSnapshot(replayQueue: PostHogReplayQueue) {
        }
    }

    private class RecordingLogger : PostHogLogger {
        private val latch = CountDownLatch(1)

        @Volatile
        var migrationThreadName: String? = null
            private set

        override fun log(message: String) {
            if (message.startsWith("Replay buffer migration skipped") || message.startsWith("Migrated")) {
                migrationThreadName = Thread.currentThread().name
                latch.countDown()
            }
        }

        override fun isEnabled(): Boolean = true

        fun awaitMigration(): Boolean = latch.await(2, TimeUnit.SECONDS)
    }

    private fun createReplayQueue(config: PostHogAndroidConfig): PostHogReplayQueue {
        return PostHogReplayQueue(config, FakeQueue(), tmpDir.newFolder().absolutePath).apply {
            bufferDelegate = NoOpBufferDelegate()
        }
    }

    private fun createTestEvent(name: String): PostHogEvent {
        return PostHogEvent(
            event = name,
            distinctId = "test-user",
            properties = mutableMapOf("test" to "value"),
            uuid = UUID.randomUUID(),
        )
    }

    @BeforeTest
    fun setUp() {
        PostHogReplayIntegration(mock<Context>(), PostHogAndroidConfig(API_KEY), MainHandler()).uninstall()
    }

    @Test
    fun `clears buffer when PostHogReplayIntegration is installed`() {
        val config = PostHogAndroidConfig(API_KEY)
        val replayQueue = createReplayQueue(config)
        config.replayQueueHolder = replayQueue
        replayQueue.add(createTestEvent("snapshot_1"))
        assertEquals(1, replayQueue.bufferDepth)

        val sut = PostHogReplayIntegration(mock<Context>(), config, MainHandler())
        sut.install(mock<PostHogInterface>())

        assertEquals(0, replayQueue.bufferDepth)

        sut.uninstall()
    }

    @Test
    fun `migrates buffered snapshots on background thread when minimum duration is met`() {
        val logger = RecordingLogger()
        val remoteConfig = mock<PostHogRemoteConfig>()
        whenever(remoteConfig.getRecordingMinimumDurationMs()).thenReturn(1L)
        val config =
            PostHogAndroidConfig(API_KEY).apply {
                this.logger = logger
                remoteConfigHolder = remoteConfig
            }
        val sut = PostHogReplayIntegration(mock<Context>(), config, MainHandler())
        sut.onRemoteConfig()
        val replayQueue = createReplayQueue(config)
        config.replayQueueHolder = replayQueue
        sut.install(mock<PostHogInterface>())
        replayQueue.add(createTestEvent("snapshot_1"))
        Thread.sleep(10)
        val callerThreadName = Thread.currentThread().name
        replayQueue.add(createTestEvent("snapshot_2"))

        assertTrue(logger.awaitMigration(), "Timed out waiting for replay buffer migration")
        assertNotEquals(callerThreadName, logger.migrationThreadName)
        assertEquals("PostHogReplayThread", logger.migrationThreadName)
    }
}

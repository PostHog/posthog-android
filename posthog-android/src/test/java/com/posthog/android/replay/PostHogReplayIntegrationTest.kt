package com.posthog.android.replay

import android.content.Context
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.PostHogEvent
import com.posthog.PostHogInterface
import com.posthog.android.API_KEY
import com.posthog.android.PostHogAndroidConfig
import com.posthog.android.createPostHogFake
import com.posthog.android.internal.MainHandler
import com.posthog.internal.PostHogLogger
import com.posthog.internal.PostHogQueueInterface
import com.posthog.internal.PostHogRemoteConfig
import com.posthog.internal.PostHogSessionManager
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [26]) // PostHogReplayIntegration.isSupported() requires API >= O.
internal class PostHogReplayIntegrationTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    private val context = mock<Context>()
    private val replayExecutors = mutableListOf<ExecutorService>()

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

    @BeforeTest
    fun `set up`() {
        PostHogSessionManager.isReactNative = false
        PostHogSessionManager.setAppInBackground(false)
        PostHogSessionManager.endSession()
    }

    @AfterTest
    fun `tear down`() {
        PostHogSessionManager.isReactNative = false
        PostHogSessionManager.endSession()
        PostHogSessionManager.setAppInBackground(true)
        replayExecutors.forEach { it.shutdownNow() }
        replayExecutors.clear()
    }

    private fun createReplayExecutor(): ExecutorService {
        val executor =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "PostHogReplayQueueIntegrationTest").apply {
                    isDaemon = true
                }
            }
        replayExecutors.add(executor)
        return executor
    }

    private fun awaitReplayExecutors() {
        replayExecutors.forEach { it.submit {}.get(2, TimeUnit.SECONDS) }
    }

    private fun createReplayQueue(config: PostHogAndroidConfig): PostHogReplayQueue {
        return PostHogReplayQueue(
            config,
            FakeQueue(),
            tmpDir.newFolder().absolutePath,
            createReplayExecutor(),
        ).apply {
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

    private fun configWithSampling(
        flagActive: Boolean,
        samplingPasses: Boolean,
        sessionReplay: Boolean = true,
    ): PostHogAndroidConfig {
        val remoteConfig =
            mock<PostHogRemoteConfig> {
                on { isSessionReplayFlagActive() } doReturn flagActive
                on { makeSamplingDecision(any()) } doReturn samplingPasses
                on { getEventTriggers() } doReturn emptySet<String>()
            }
        return PostHogAndroidConfig(API_KEY).apply {
            remoteConfigHolder = remoteConfig
            this.sessionReplay = sessionReplay
        }
    }

    private fun getSut(config: PostHogAndroidConfig = PostHogAndroidConfig(API_KEY)): PostHogReplayIntegration {
        return PostHogReplayIntegration(context, config, MainHandler())
    }

    @Test
    fun `onSessionIdChanged starts replay when previously inactive and sampling passes`() {
        // The prior session may have been sampled out; rotation must re-evaluate sampling and
        // start replay even though isSessionReplayActive was false.
        val sut = getSut(configWithSampling(flagActive = true, samplingPasses = true))
        val fake = createPostHogFake()
        fake.sessionReplayActive = false
        sut.install(fake)
        try {
            PostHogSessionManager.startSession()
            sut.onSessionIdChanged()
            shadowOf(Looper.getMainLooper()).idle()

            assertTrue(sut.isActive())
        } finally {
            sut.uninstall()
        }
    }

    @Test
    fun `onSessionIdChanged stops then starts replay when active and sampling passes`() {
        val sut = getSut(configWithSampling(flagActive = true, samplingPasses = true))
        val fake = createPostHogFake()
        sut.install(fake)
        try {
            PostHogSessionManager.startSession()
            // Pre-activate replay so we can verify it's stopped+restarted, not just left running.
            sut.start(resumeCurrent = true)
            assertTrue(sut.isActive())

            sut.onSessionIdChanged()
            shadowOf(Looper.getMainLooper()).idle()

            assertTrue(sut.isActive())
        } finally {
            sut.uninstall()
        }
    }

    @Test
    fun `onSessionIdChanged stops replay when sampling fails`() {
        val sut = getSut(configWithSampling(flagActive = true, samplingPasses = false))
        val fake = createPostHogFake()
        sut.install(fake)
        try {
            PostHogSessionManager.startSession()
            sut.start(resumeCurrent = true)
            assertTrue(sut.isActive())

            sut.onSessionIdChanged()
            shadowOf(Looper.getMainLooper()).idle()

            assertFalse(sut.isActive())
        } finally {
            sut.uninstall()
        }
    }

    @Test
    fun `onSessionIdChanged stops replay when session is cleared`() {
        val sut = getSut(configWithSampling(flagActive = true, samplingPasses = true))
        val fake = createPostHogFake()
        sut.install(fake)
        try {
            PostHogSessionManager.startSession()
            sut.start(resumeCurrent = true)
            assertTrue(sut.isActive())

            // Clear the session, then fire onSessionIdChanged — peekSessionId returns null.
            PostHogSessionManager.endSession()
            sut.onSessionIdChanged()
            shadowOf(Looper.getMainLooper()).idle()

            assertFalse(sut.isActive())
        } finally {
            sut.uninstall()
        }
    }

    @Test
    fun `onSessionIdChanged does not start replay when flag is disabled`() {
        val sut = getSut(configWithSampling(flagActive = false, samplingPasses = true))
        val fake = createPostHogFake()
        sut.install(fake)
        try {
            PostHogSessionManager.startSession()
            sut.onSessionIdChanged()
            shadowOf(Looper.getMainLooper()).idle()

            assertFalse(sut.isActive())
        } finally {
            sut.uninstall()
        }
    }

    @Test
    fun `onSessionIdChanged does not auto-start replay when config sessionReplay is false`() {
        // config.sessionReplay is the master switch — even if remote flag and sampling both
        // pass, we must not auto-start replay if the customer disabled it at config level.
        val sut =
            getSut(
                configWithSampling(
                    flagActive = true,
                    samplingPasses = true,
                    sessionReplay = false,
                ),
            )
        val fake = createPostHogFake()
        sut.install(fake)
        try {
            PostHogSessionManager.startSession()
            sut.onSessionIdChanged()
            shadowOf(Looper.getMainLooper()).idle()

            assertFalse(sut.isActive())
        } finally {
            sut.uninstall()
        }
    }

    @Test
    fun `onSessionIdChanged stops active replay on rotation when config sessionReplay is false`() {
        // Defensive: if replay was somehow started (e.g. trigger-matched, or pre-config-flip),
        // a rotation under config.sessionReplay = false should stop it rather than restart.
        val sut =
            getSut(
                configWithSampling(
                    flagActive = true,
                    samplingPasses = true,
                    sessionReplay = false,
                ),
            )
        val fake = createPostHogFake()
        sut.install(fake)
        try {
            PostHogSessionManager.startSession()
            sut.start(resumeCurrent = true)
            assertTrue(sut.isActive())

            sut.onSessionIdChanged()
            shadowOf(Looper.getMainLooper()).idle()

            assertFalse(sut.isActive())
        } finally {
            sut.uninstall()
        }
    }

    @Test
    fun `clears buffer when PostHogReplayIntegration is installed`() {
        val config = PostHogAndroidConfig(API_KEY)
        val replayQueue = createReplayQueue(config)
        config.replayQueueHolder = replayQueue
        replayQueue.add(createTestEvent("snapshot_1"))
        awaitReplayExecutors()
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
        awaitReplayExecutors()
        Thread.sleep(10)
        val callerThreadName = Thread.currentThread().name
        replayQueue.add(createTestEvent("snapshot_2"))

        assertTrue(logger.awaitMigration(), "Timed out waiting for replay buffer migration")
        assertNotEquals(callerThreadName, logger.migrationThreadName)
        assertEquals("PostHogReplayThread", logger.migrationThreadName)
        sut.uninstall()
    }

    @Test
    fun `resets buffer state when session id changes`() {
        val logger = RecordingLogger()
        val remoteConfig = mock<PostHogRemoteConfig>()
        whenever(remoteConfig.getRecordingMinimumDurationMs()).thenReturn(1L)
        val config =
            PostHogAndroidConfig(API_KEY).apply {
                this.logger = logger
                remoteConfigHolder = remoteConfig
            }
        val firstSessionId = UUID.randomUUID()
        val secondSessionId = UUID.randomUUID()
        val postHog = mock<PostHogInterface>()
        whenever(postHog.getSessionId()).thenReturn(firstSessionId)
        val sut = PostHogReplayIntegration(mock<Context>(), config, MainHandler())
        val replayQueue = createReplayQueue(config)
        config.replayQueueHolder = replayQueue
        PostHogSessionManager.setSessionId(firstSessionId)
        sut.install(postHog)
        sut.start(resumeCurrent = true)

        replayQueue.add(createTestEvent("snapshot_1"))
        awaitReplayExecutors()
        Thread.sleep(10)
        replayQueue.add(createTestEvent("snapshot_2"))

        assertTrue(logger.awaitMigration(), "Timed out waiting for replay buffer migration")
        assertEquals(2, replayQueue.bufferDepth)

        PostHogSessionManager.setSessionId(secondSessionId)
        whenever(postHog.getSessionId()).thenReturn(secondSessionId)
        sut.onSessionIdChanged()

        assertEquals(0, replayQueue.bufferDepth)

        replayQueue.add(createTestEvent("snapshot_new_session"))
        awaitReplayExecutors()

        assertEquals(1, replayQueue.bufferDepth)
        sut.uninstall()
    }
}

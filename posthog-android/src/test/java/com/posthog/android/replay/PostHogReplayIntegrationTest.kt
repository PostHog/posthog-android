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
import com.posthog.internal.EndpointSpec
import com.posthog.internal.PostHogApi
import com.posthog.internal.PostHogLogger
import com.posthog.internal.PostHogQueue
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

    private class FakeQueue : PostHogQueueInterface<PostHogEvent> {
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
        override val isActive: Boolean = true

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
                on { hasRemoteConfigFetched() } doReturn true
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
        whenever(remoteConfig.hasRemoteConfigFetched()).thenReturn(true)
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
        // Min-duration migration only persists while recording is active.
        sut.start(resumeCurrent = true)
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
        whenever(remoteConfig.hasRemoteConfigFetched()).thenReturn(true)
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

    private data class RealQueueFixture(
        val sut: PostHogReplayIntegration,
        val replayQueue: PostHogReplayQueue,
        val config: PostHogAndroidConfig,
    )

    private fun createIntegrationWithRealQueue(
        flagActive: Boolean,
        hasFetched: Boolean,
        minimumDurationMs: Long? = null,
        sessionReplay: Boolean = true,
        samplingPasses: Boolean = true,
    ): RealQueueFixture {
        val remoteConfig =
            mock<PostHogRemoteConfig> {
                on { isSessionReplayFlagActive() } doReturn flagActive
                on { hasRemoteConfigFetched() } doReturn hasFetched
                on { makeSamplingDecision(any()) } doReturn samplingPasses
                on { getEventTriggers() } doReturn emptySet<String>()
                on { getRecordingMinimumDurationMs() } doReturn minimumDurationMs
            }
        val config =
            PostHogAndroidConfig(API_KEY).apply {
                remoteConfigHolder = remoteConfig
                this.sessionReplay = sessionReplay
            }
        val storagePrefix = tmpDir.newFolder().absolutePath
        val executor = createReplayExecutor()
        val innerQueue =
            PostHogQueue(
                config,
                EndpointSpec.snapshot(config, PostHogApi(config), storagePrefix),
                executor,
            )
        val replayQueue = PostHogReplayQueue(config, innerQueue, storagePrefix, executor)
        config.replayQueueHolder = replayQueue
        val sut = PostHogReplayIntegration(mock<Context>(), config, MainHandler())
        return RealQueueFixture(sut, replayQueue, config)
    }

    private fun awaitCondition(
        timeoutMs: Long = 2_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!condition()) {
            if (System.nanoTime() > deadline) {
                error("Timed out waiting for condition")
            }
            Thread.sleep(10)
        }
    }

    @Test
    fun `routes snapshots to buffer while awaiting first remote config`() {
        val fx = createIntegrationWithRealQueue(flagActive = true, hasFetched = false)
        fx.sut.install(mock<PostHogInterface>())
        try {
            fx.replayQueue.add(createTestEvent("snapshot_1"))
            fx.replayQueue.add(createTestEvent("snapshot_2"))
            awaitReplayExecutors()

            assertEquals(2, fx.replayQueue.bufferDepth)
            assertEquals(0, fx.replayQueue.depth)
        } finally {
            fx.sut.uninstall()
        }
    }

    @Test
    fun `first remote config with flag on migrates buffer to inner queue`() {
        val fx = createIntegrationWithRealQueue(flagActive = true, hasFetched = false)
        val postHog = mock<PostHogInterface>()
        whenever(postHog.getSessionId()).thenReturn(UUID.randomUUID())
        fx.sut.install(postHog)
        try {
            fx.replayQueue.add(createTestEvent("snapshot_1"))
            Thread.sleep(5)
            fx.replayQueue.add(createTestEvent("snapshot_2"))
            awaitReplayExecutors()
            assertEquals(2, fx.replayQueue.bufferDepth)

            fx.sut.onRemoteConfig()

            awaitCondition { fx.replayQueue.bufferDepth == 0 && fx.replayQueue.depth == 2 }
            assertEquals(0, fx.replayQueue.bufferDepth)
            assertEquals(2, fx.replayQueue.depth)
        } finally {
            fx.sut.uninstall()
        }
    }

    @Test
    fun `first remote config with flag off clears buffer and stops recording`() {
        val fx = createIntegrationWithRealQueue(flagActive = false, hasFetched = false)
        fx.sut.install(mock<PostHogInterface>())
        fx.sut.start(resumeCurrent = true)
        try {
            assertTrue(fx.sut.isActive())

            fx.replayQueue.add(createTestEvent("snapshot_1"))
            fx.replayQueue.add(createTestEvent("snapshot_2"))
            awaitReplayExecutors()
            assertEquals(2, fx.replayQueue.bufferDepth)

            fx.sut.onRemoteConfig()
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(0, fx.replayQueue.bufferDepth)
            assertEquals(0, fx.replayQueue.depth)
            assertFalse(fx.sut.isActive())
        } finally {
            fx.sut.uninstall()
        }
    }

    @Test
    fun `onRemoteConfig stops active recording when flag turns off after config already fetched`() {
        val fx = createIntegrationWithRealQueue(flagActive = false, hasFetched = true)
        fx.sut.install(mock<PostHogInterface>())
        fx.sut.start(resumeCurrent = true)
        try {
            assertTrue(fx.sut.isActive())

            fx.sut.onRemoteConfig()
            shadowOf(Looper.getMainLooper()).idle()

            assertFalse(fx.sut.isActive())
        } finally {
            fx.sut.uninstall()
        }
    }

    @Test
    fun `onRemoteConfig resumes recording when flag turns on and recording inactive`() {
        val fx = createIntegrationWithRealQueue(flagActive = true, hasFetched = true)
        val postHog = mock<PostHogInterface>()
        whenever(postHog.getSessionId()).thenReturn(UUID.randomUUID())
        fx.sut.install(postHog)
        try {
            assertFalse(fx.sut.isActive())

            fx.sut.onRemoteConfig()
            shadowOf(Looper.getMainLooper()).idle()

            assertTrue(fx.sut.isActive())
        } finally {
            fx.sut.uninstall()
        }
    }

    @Test
    fun `first remote config flag on under min duration keeps buffering instead of flushing`() {
        val fx =
            createIntegrationWithRealQueue(
                flagActive = true,
                hasFetched = false,
                minimumDurationMs = 60_000L,
            )
        val postHog = mock<PostHogInterface>()
        whenever(postHog.getSessionId()).thenReturn(UUID.randomUUID())
        fx.sut.install(postHog)
        fx.sut.start(resumeCurrent = true)
        try {
            fx.replayQueue.add(createTestEvent("snapshot_1"))
            Thread.sleep(5)
            fx.replayQueue.add(createTestEvent("snapshot_2"))
            awaitReplayExecutors()
            assertEquals(2, fx.replayQueue.bufferDepth)

            fx.sut.onRemoteConfig()
            shadowOf(Looper.getMainLooper()).idle()

            // Flag on but session is far under the 60s minimum: the opening window must stay buffered
            // (handed to the min-duration gate), not be force-flushed to the persisted queue.
            assertEquals(2, fx.replayQueue.bufferDepth)
            assertEquals(0, fx.replayQueue.depth)
            assertTrue(fx.sut.isActive())
        } finally {
            fx.sut.uninstall()
        }
    }

    @Test
    fun `first remote config flag on with min duration already met migrates immediately`() {
        val fx =
            createIntegrationWithRealQueue(
                flagActive = true,
                hasFetched = false,
                minimumDurationMs = 10L,
            )
        val postHog = mock<PostHogInterface>()
        whenever(postHog.getSessionId()).thenReturn(UUID.randomUUID())
        fx.sut.install(postHog)
        fx.sut.start(resumeCurrent = true)
        try {
            fx.replayQueue.add(createTestEvent("snapshot_1"))
            Thread.sleep(30)
            fx.replayQueue.add(createTestEvent("snapshot_2"))
            awaitReplayExecutors()
            assertEquals(2, fx.replayQueue.bufferDepth)

            fx.sut.onRemoteConfig()

            // Buffered window already spans the 10ms minimum -> migrate on resolve.
            awaitCondition { fx.replayQueue.bufferDepth == 0 && fx.replayQueue.depth == 2 }
            assertEquals(0, fx.replayQueue.bufferDepth)
            assertEquals(2, fx.replayQueue.depth)
        } finally {
            fx.sut.uninstall()
        }
    }

    @Test
    fun `flag on resolve under min duration then migrates whole window once min duration accrues`() {
        val fx =
            createIntegrationWithRealQueue(
                flagActive = true,
                hasFetched = false,
                minimumDurationMs = 10L,
            )
        val postHog = mock<PostHogInterface>()
        whenever(postHog.getSessionId()).thenReturn(UUID.randomUUID())
        fx.sut.install(postHog)
        fx.sut.start(resumeCurrent = true)
        try {
            fx.replayQueue.add(createTestEvent("snapshot_1"))
            awaitReplayExecutors()

            // First config resolves flag-on while under the 10ms minimum: keep buffering, disarm.
            fx.sut.onRemoteConfig()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, fx.replayQueue.bufferDepth)
            assertEquals(0, fx.replayQueue.depth)

            // A later snapshot pushes the window (from the opening frame) past the minimum: the whole
            // window migrates, proving awaitingFirstRemoteConfig was disarmed and the opening frame
            // was preserved and counted from session start.
            Thread.sleep(20)
            fx.replayQueue.add(createTestEvent("snapshot_2"))

            awaitCondition { fx.replayQueue.bufferDepth == 0 && fx.replayQueue.depth == 2 }
            assertEquals(2, fx.replayQueue.depth)
        } finally {
            fx.sut.uninstall()
        }
    }

    @Test
    fun `onReplayBufferSnapshot does not migrate when recording is inactive`() {
        val fx =
            createIntegrationWithRealQueue(
                flagActive = true,
                hasFetched = true,
                minimumDurationMs = 1L,
            )
        val postHog = mock<PostHogInterface>()
        whenever(postHog.getSessionId()).thenReturn(UUID.randomUUID())
        fx.sut.install(postHog)
        fx.sut.start(resumeCurrent = true)
        try {
            fx.replayQueue.add(createTestEvent("snapshot_1"))
            awaitReplayExecutors()
            Thread.sleep(10)

            // Recording stops, then a late snapshot fires onReplayBufferSnapshot with the 1ms minimum
            // already met — the inactive guard must prevent it migrating to the persisted queue.
            fx.sut.stop()
            fx.replayQueue.add(createTestEvent("snapshot_2"))
            awaitReplayExecutors()

            assertEquals(0, fx.replayQueue.depth)
            assertEquals(2, fx.replayQueue.bufferDepth)
        } finally {
            fx.sut.uninstall()
        }
    }

    @Test
    fun `min duration elapsed while awaiting first remote config does not migrate`() {
        val fx =
            createIntegrationWithRealQueue(
                flagActive = true,
                hasFetched = false,
                minimumDurationMs = 1L,
            )
        fx.sut.install(mock<PostHogInterface>())
        try {
            fx.replayQueue.add(createTestEvent("snapshot_1"))
            Thread.sleep(10)
            fx.replayQueue.add(createTestEvent("snapshot_2"))
            awaitReplayExecutors()

            // Min duration (1ms) has elapsed, but the migrate trigger must stay gated until the
            // first remote config resolves — otherwise the stale-cache buffer leaks to the queue.
            assertEquals(2, fx.replayQueue.bufferDepth)
            assertEquals(0, fx.replayQueue.depth)
        } finally {
            fx.sut.uninstall()
        }
    }

    @Test
    fun `first remote config flag on but sampled out drops buffer and stops recording`() {
        val fx =
            createIntegrationWithRealQueue(
                flagActive = true,
                hasFetched = false,
                samplingPasses = false,
            )
        val postHog = mock<PostHogInterface>()
        whenever(postHog.getSessionId()).thenReturn(UUID.randomUUID())
        fx.sut.install(postHog)
        fx.sut.start(resumeCurrent = true)
        try {
            assertTrue(fx.sut.isActive())

            fx.replayQueue.add(createTestEvent("snapshot_1"))
            fx.replayQueue.add(createTestEvent("snapshot_2"))
            awaitReplayExecutors()
            assertEquals(2, fx.replayQueue.bufferDepth)

            // Flag stays on but the fresh config samples this session out: the opening window must be
            // discarded, not migrated to the persisted (and sent) queue.
            fx.sut.onRemoteConfig()
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(0, fx.replayQueue.bufferDepth)
            assertEquals(0, fx.replayQueue.depth)
            assertFalse(fx.sut.isActive())
        } finally {
            fx.sut.uninstall()
        }
    }

    @Test
    fun `onRemoteConfigFailed with cached flag on migrates buffer and keeps recording`() {
        val fx = createIntegrationWithRealQueue(flagActive = true, hasFetched = false)
        val postHog = mock<PostHogInterface>()
        whenever(postHog.getSessionId()).thenReturn(UUID.randomUUID())
        fx.sut.install(postHog)
        fx.sut.start(resumeCurrent = true)
        try {
            assertTrue(fx.sut.isActive())

            fx.replayQueue.add(createTestEvent("snapshot_1"))
            Thread.sleep(5)
            fx.replayQueue.add(createTestEvent("snapshot_2"))
            awaitReplayExecutors()
            assertEquals(2, fx.replayQueue.bufferDepth)

            // First config fetch failed (offline): fall back to the cached (on) flag — keep recording
            // and migrate the buffered opening window rather than dropping the offline session.
            fx.sut.onRemoteConfigFailed()

            awaitCondition { fx.replayQueue.bufferDepth == 0 && fx.replayQueue.depth == 2 }
            assertEquals(0, fx.replayQueue.bufferDepth)
            assertEquals(2, fx.replayQueue.depth)
            assertTrue(fx.sut.isActive())
        } finally {
            fx.sut.uninstall()
        }
    }

    @Test
    fun `onRemoteConfigFailed with cached flag off drops buffer and stops recording`() {
        val fx = createIntegrationWithRealQueue(flagActive = false, hasFetched = false)
        fx.sut.install(mock<PostHogInterface>())
        fx.sut.start(resumeCurrent = true)
        try {
            assertTrue(fx.sut.isActive())

            fx.replayQueue.add(createTestEvent("snapshot_1"))
            fx.replayQueue.add(createTestEvent("snapshot_2"))
            awaitReplayExecutors()
            assertEquals(2, fx.replayQueue.bufferDepth)

            // First config fetch failed and the cached flag is off: drop the buffer and stop.
            fx.sut.onRemoteConfigFailed()
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(0, fx.replayQueue.bufferDepth)
            assertEquals(0, fx.replayQueue.depth)
            assertFalse(fx.sut.isActive())
        } finally {
            fx.sut.uninstall()
        }
    }

    @Test
    fun `onRemoteConfigFailed with cached flag on but sampled out drops buffer and stops recording`() {
        val fx =
            createIntegrationWithRealQueue(
                flagActive = true,
                hasFetched = false,
                samplingPasses = false,
            )
        val postHog = mock<PostHogInterface>()
        whenever(postHog.getSessionId()).thenReturn(UUID.randomUUID())
        fx.sut.install(postHog)
        fx.sut.start(resumeCurrent = true)
        try {
            assertTrue(fx.sut.isActive())

            fx.replayQueue.add(createTestEvent("snapshot_1"))
            fx.replayQueue.add(createTestEvent("snapshot_2"))
            awaitReplayExecutors()
            assertEquals(2, fx.replayQueue.bufferDepth)

            // Fetch failed; cached flag is on but the cached sample rate excludes this session —
            // the fallback must respect sampling and drop + stop, not migrate.
            fx.sut.onRemoteConfigFailed()
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(0, fx.replayQueue.bufferDepth)
            assertEquals(0, fx.replayQueue.depth)
            assertFalse(fx.sut.isActive())
        } finally {
            fx.sut.uninstall()
        }
    }

    @Test
    fun `onRemoteConfigFailed after first config already resolved is a no-op`() {
        val fx = createIntegrationWithRealQueue(flagActive = true, hasFetched = false)
        val postHog = mock<PostHogInterface>()
        whenever(postHog.getSessionId()).thenReturn(UUID.randomUUID())
        fx.sut.install(postHog)
        fx.sut.start(resumeCurrent = true)
        try {
            fx.replayQueue.add(createTestEvent("snapshot_1"))
            Thread.sleep(5)
            fx.replayQueue.add(createTestEvent("snapshot_2"))
            awaitReplayExecutors()

            // First live config resolves and migrates the window.
            fx.sut.onRemoteConfig()
            awaitCondition { fx.replayQueue.bufferDepth == 0 && fx.replayQueue.depth == 2 }

            // A late failure callback after the gate already resolved must not disturb queue state.
            fx.sut.onRemoteConfigFailed()
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(0, fx.replayQueue.bufferDepth)
            assertEquals(2, fx.replayQueue.depth)
            assertTrue(fx.sut.isActive())
        } finally {
            fx.sut.uninstall()
        }
    }
}

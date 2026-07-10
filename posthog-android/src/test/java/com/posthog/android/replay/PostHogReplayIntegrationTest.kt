package com.posthog.android.replay

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.Window
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.PostHogEvent
import com.posthog.PostHogFake
import com.posthog.PostHogInterface
import com.posthog.android.API_KEY
import com.posthog.android.PostHogAndroidConfig
import com.posthog.android.createPostHogFake
import com.posthog.android.internal.MainHandler
import com.posthog.android.replay.internal.NextDrawListener
import com.posthog.android.replay.internal.ViewTreeSnapshotStatus
import com.posthog.internal.EndpointSpec
import com.posthog.internal.PostHogApi
import com.posthog.internal.PostHogLogger
import com.posthog.internal.PostHogMemoryPreferences
import com.posthog.internal.PostHogNetworkStatus
import com.posthog.internal.PostHogOnRemoteConfigLoaded
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
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPixelCopy
import java.lang.ref.WeakReference
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
        val innerQueue: PostHogQueue<PostHogEvent>,
        val config: PostHogAndroidConfig,
        val remoteConfig: PostHogRemoteConfig,
    )

    private fun createIntegrationWithRealQueue(
        flagActive: Boolean,
        hasFetched: Boolean,
        minimumDurationMs: Long? = null,
        sessionReplay: Boolean = true,
        samplingPasses: Boolean = true,
        preloadFeatureFlags: Boolean = true,
        integrationContext: Context = mock<Context>(),
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
                this.preloadFeatureFlags = preloadFeatureFlags
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
        val sut = PostHogReplayIntegration(integrationContext, config, MainHandler())
        return RealQueueFixture(sut, replayQueue, innerQueue, config, remoteConfig)
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
    fun `first remote config flag off clears the buffer but leaves already-persisted events untouched`() {
        // Guards clearBuffer()'s scoping: a fresh-off resolve must drop the buffered opening window
        // WITHOUT touching events already persisted to the inner send queue. Seeding the inner queue
        // first makes a clearBuffer()->clear() regression (silent data loss) fail this test.
        val fx = createIntegrationWithRealQueue(flagActive = false, hasFetched = false)
        fx.sut.install(mock<PostHogInterface>())
        fx.sut.start(resumeCurrent = true)
        try {
            // Pre-seed the persisted inner queue as if earlier events had already been sent-queued.
            fx.innerQueue.add(createTestEvent("persisted_1"))
            fx.innerQueue.add(createTestEvent("persisted_2"))
            awaitCondition { fx.replayQueue.depth == 2 }

            fx.replayQueue.add(createTestEvent("buffered_1"))
            fx.replayQueue.add(createTestEvent("buffered_2"))
            awaitReplayExecutors()
            assertEquals(2, fx.replayQueue.bufferDepth)

            fx.sut.onRemoteConfig()
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(0, fx.replayQueue.bufferDepth)
            // The pre-existing persisted events survive — clearBuffer() only drops the buffer.
            assertEquals(2, fx.replayQueue.depth)
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
    fun `onRemoteConfig loaded false with cached flag on migrates buffer and keeps recording`() {
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
            fx.sut.onRemoteConfig(loaded = false)

            awaitCondition { fx.replayQueue.bufferDepth == 0 && fx.replayQueue.depth == 2 }
            assertEquals(0, fx.replayQueue.bufferDepth)
            assertEquals(2, fx.replayQueue.depth)
            assertTrue(fx.sut.isActive())
        } finally {
            fx.sut.uninstall()
        }
    }

    @Test
    fun `onRemoteConfig loaded false with cached flag off drops buffer and stops recording`() {
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
            fx.sut.onRemoteConfig(loaded = false)
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(0, fx.replayQueue.bufferDepth)
            assertEquals(0, fx.replayQueue.depth)
            assertFalse(fx.sut.isActive())
        } finally {
            fx.sut.uninstall()
        }
    }

    @Test
    fun `onRemoteConfig loaded false with cached flag on but sampled out drops buffer and stops recording`() {
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
            fx.sut.onRemoteConfig(loaded = false)
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(0, fx.replayQueue.bufferDepth)
            assertEquals(0, fx.replayQueue.depth)
            assertFalse(fx.sut.isActive())
        } finally {
            fx.sut.uninstall()
        }
    }

    @Test
    fun `single onRemoteConfig delivery resolves the gate without a second callback`() {
        // Regression guard for the linkedFlag-deferral bug: at cold start loadRemoteConfig loads
        // /flags nested (notifyRemoteConfigLoaded=false) and fires exactly one onRemoteConfig. A
        // single delivery must fully resolve the gate — migrate the buffered window and disarm — so
        // later snapshots persist instead of buffering forever.
        val fx = createIntegrationWithRealQueue(flagActive = true, hasFetched = false)
        val postHog = mock<PostHogInterface>()
        whenever(postHog.getSessionId()).thenReturn(UUID.randomUUID())
        fx.sut.install(postHog)
        try {
            fx.replayQueue.add(createTestEvent("snapshot_1"))
            fx.replayQueue.add(createTestEvent("snapshot_2"))
            awaitReplayExecutors()
            assertEquals(2, fx.replayQueue.bufferDepth)

            // The live config has resolved by the time onRemoteConfig fires (loadRemoteConfig marks
            // hasRemoteConfigFetched before notifying), so the gate stays disarmed after this delivery.
            whenever(fx.remoteConfig.hasRemoteConfigFetched()).thenReturn(true)
            fx.sut.onRemoteConfig()
            shadowOf(Looper.getMainLooper()).idle()

            awaitCondition { fx.replayQueue.bufferDepth == 0 && fx.replayQueue.depth == 2 }

            // Gate disarmed on the single delivery: a snapshot added afterwards persists straight to
            // the inner queue instead of routing back into the buffer.
            fx.replayQueue.add(createTestEvent("snapshot_3"))
            awaitCondition { fx.replayQueue.depth == 3 }
            assertEquals(0, fx.replayQueue.bufferDepth)
        } finally {
            fx.sut.uninstall()
        }
    }

    @Test
    fun `mid-session flag off stops persisting synchronously before the posted stop runs`() {
        // reevaluateRecordingState flips the active gate synchronously so add() on the replay
        // executor stops persisting immediately, without waiting for the posted stop() to run on
        // main — otherwise snapshots would leak to the send queue after the server said off.
        val fx = createIntegrationWithRealQueue(flagActive = false, hasFetched = true)
        val postHog = mock<PostHogInterface>()
        whenever(postHog.getSessionId()).thenReturn(UUID.randomUUID())
        fx.sut.install(postHog)
        fx.sut.start(resumeCurrent = true)
        try {
            assertTrue(fx.sut.isActive())

            // Deliver the fresh flag-off config but do NOT idle the main looper, so the posted stop()
            // has not run yet.
            fx.sut.onRemoteConfig()

            // The active gate must already be false synchronously.
            assertFalse(fx.sut.isActive())

            // A snapshot added in this window must not be persisted.
            fx.replayQueue.add(createTestEvent("late_snapshot"))
            awaitReplayExecutors()
            assertEquals(0, fx.replayQueue.depth)
        } finally {
            fx.sut.uninstall()
        }
    }

    @Test
    fun `gate does not arm when neither remoteConfig nor preloadFeatureFlags will fetch`() {
        // With both remoteConfig and preloadFeatureFlags disabled, setup dispatches no /config or
        // /flags request, so no onRemoteConfig callback arrives to disarm the gate. Arming it would
        // buffer every snapshot for the whole session; instead it must stay disarmed and persist.
        val remoteConfigMock =
            mock<PostHogRemoteConfig> {
                on { isSessionReplayFlagActive() } doReturn true
                on { hasRemoteConfigFetched() } doReturn false
                on { makeSamplingDecision(any()) } doReturn true
                on { getEventTriggers() } doReturn emptySet<String>()
                on { getRecordingMinimumDurationMs() } doReturn null
            }
        val config =
            PostHogAndroidConfig(API_KEY).apply {
                remoteConfigHolder = remoteConfigMock
                sessionReplay = true
                @Suppress("DEPRECATION")
                remoteConfig = false
                preloadFeatureFlags = false
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
        val postHog = mock<PostHogInterface>()
        whenever(postHog.getSessionId()).thenReturn(UUID.randomUUID())
        sut.install(postHog)
        sut.start(resumeCurrent = true)
        try {
            // No remote-config callback will ever fire, so buffering must not be armed.
            replayQueue.add(createTestEvent("snapshot_1"))
            awaitCondition { replayQueue.depth == 1 }
            assertEquals(0, replayQueue.bufferDepth)
            assertEquals(1, replayQueue.depth)
        } finally {
            sut.uninstall()
        }
    }

    @Test
    fun `mid-session resume forces a fresh keyframe by clearing per-view snapshot state`() {
        // A mid-session flag-on resume must emit a fresh full snapshot: while stopped, per-view state
        // is frozen and can reference a full snapshot that was never delivered (e.g. a dropped
        // first-config-off window), so resuming against it would emit orphaned incremental snapshots.
        val fx = createIntegrationWithRealQueue(flagActive = true, hasFetched = true)
        val postHog = mock<PostHogInterface>()
        whenever(postHog.getSessionId()).thenReturn(UUID.randomUUID())
        fx.sut.install(postHog)
        fx.sut.start(resumeCurrent = true)
        try {
            assertTrue(fx.sut.isActive())

            // Simulate a decor view that already emitted a full snapshot before the stop.
            val view = View(ApplicationProvider.getApplicationContext())
            val status =
                ViewTreeSnapshotStatus(
                    mock<NextDrawListener>(),
                    sentFullSnapshot = true,
                    sentMetaEvent = true,
                )
            fx.sut.decorViews[view] = status

            // Flag turns off mid-session -> stop. stop() intentionally does NOT clear per-view state.
            whenever(fx.remoteConfig.isSessionReplayFlagActive()).thenReturn(false)
            fx.sut.onRemoteConfig()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(fx.sut.isActive())
            assertTrue(status.sentFullSnapshot)

            // Flag turns back on -> resume must reset the per-view state so the next snapshot is full.
            whenever(fx.remoteConfig.isSessionReplayFlagActive()).thenReturn(true)
            fx.sut.onRemoteConfig()
            shadowOf(Looper.getMainLooper()).idle()

            assertTrue(fx.sut.isActive())
            assertFalse(status.sentFullSnapshot)
            assertFalse(status.sentMetaEvent)
        } finally {
            fx.sut.uninstall()
        }
    }

    @Test
    fun `real remote config resolves the replay buffer end to end via the production dispatch`() {
        // End-to-end through the production pipeline: a real PostHogRemoteConfig wired to the replay
        // integration by the same onRemoteConfig(loaded) dispatch PostHog.setup uses. Offline, the
        // attempt terminally fails -> onRemoteConfig(loaded = false) -> the integration falls back to
        // the disk-cached flag (on here) and migrates the buffered opening window.
        val preferences = PostHogMemoryPreferences()
        preferences.setValue("sessionReplay", mapOf("endpoint" to "/b/"))

        val offline =
            object : PostHogNetworkStatus {
                override fun isConnected() = false
            }
        val config =
            PostHogAndroidConfig(API_KEY, host = "http://localhost").apply {
                cachePreferences = preferences
                networkStatus = offline
                sessionReplay = true
            }

        val storagePrefix = tmpDir.newFolder().absolutePath
        val queueExecutor = createReplayExecutor()
        val innerQueue =
            PostHogQueue(
                config,
                EndpointSpec.snapshot(config, PostHogApi(config), storagePrefix),
                queueExecutor,
            )
        val replayQueue = PostHogReplayQueue(config, innerQueue, storagePrefix, queueExecutor)
        config.replayQueueHolder = replayQueue
        val sut = PostHogReplayIntegration(mock<Context>(), config, MainHandler())

        // Wire the real remote config with the production dispatch: loaded = hasRemoteConfigFetched().
        val remoteConfigExecutor = createReplayExecutor()
        lateinit var remoteConfig: PostHogRemoteConfig
        remoteConfig =
            PostHogRemoteConfig(
                config,
                PostHogApi(config),
                remoteConfigExecutor,
                onRemoteConfigLoaded = PostHogOnRemoteConfigLoaded { sut.onRemoteConfig(loaded = remoteConfig.hasRemoteConfigFetched()) },
            )
        config.remoteConfigHolder = remoteConfig

        val postHog = mock<PostHogInterface>()
        whenever(postHog.getSessionId()).thenReturn(UUID.randomUUID())
        sut.install(postHog)
        try {
            replayQueue.add(createTestEvent("snapshot_1"))
            replayQueue.add(createTestEvent("snapshot_2"))
            awaitReplayExecutors()
            assertEquals(2, replayQueue.bufferDepth)

            // Drive the real (offline) remote config resolution through the production callback.
            remoteConfig.loadRemoteConfig("distinct-id", anonymousId = null, groups = null)

            awaitCondition { replayQueue.bufferDepth == 0 && replayQueue.depth == 2 }
            assertEquals(0, replayQueue.bufferDepth)
            assertEquals(2, replayQueue.depth)
        } finally {
            sut.uninstall()
            remoteConfig.clear()
        }
    }

    @Test
    fun `boolean config first remote config with flag off still stops via buffer-resolve path`() {
        // No linkedFlag involved: the first onRemoteConfig is exempt from reevaluateRecordingState's
        // stop step (Decision 3), but the buffer-resolve path still stops on not-recordable. The
        // exemption applies to the mid-session stop; the cleanup stop on a not-recordable resolve fires.
        val fx = createIntegrationWithRealQueue(flagActive = false, hasFetched = false)
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
    fun `onRemoteConfig loaded false after first config already resolved is a no-op`() {
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
            fx.sut.onRemoteConfig(loaded = false)
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(0, fx.replayQueue.bufferDepth)
            assertEquals(2, fx.replayQueue.depth)
            assertTrue(fx.sut.isActive())
        } finally {
            fx.sut.uninstall()
        }
    }

    @Test
    fun `re-armed gate after reset resolves from cached config on the next flags reload with cached on`() {
        // Req 5: reset() clears PostHogRemoteConfig's fetched latch, and the silent session rotation
        // that follows re-arms the cold-start gate via resetBufferingState. The next /flags reload —
        // represented here by onRemoteConfig — must resolve the re-armed gate against the cached
        // sessionRecording config without waiting for a new /config fetch. Cached-on migrates the
        // post-reset opening window.
        val fx = createIntegrationWithRealQueue(flagActive = true, hasFetched = true)
        val firstSessionId = UUID.randomUUID()
        val postHog = mock<PostHogInterface>()
        whenever(postHog.getSessionId()).thenReturn(firstSessionId)
        PostHogSessionManager.setSessionId(firstSessionId)
        fx.sut.install(postHog)
        fx.sut.start(resumeCurrent = true)
        try {
            fx.sut.onRemoteConfig()
            assertTrue(fx.sut.isActive())

            // reset()/identify(newUser) flips the fetched latch back and rotates the session id.
            whenever(fx.remoteConfig.hasRemoteConfigFetched()).thenReturn(false)
            val secondSessionId = UUID.randomUUID()
            whenever(postHog.getSessionId()).thenReturn(secondSessionId)
            PostHogSessionManager.setSessionId(secondSessionId)
            fx.sut.onSessionIdChanged()

            fx.replayQueue.add(createTestEvent("snapshot_after_reset_1"))
            fx.replayQueue.add(createTestEvent("snapshot_after_reset_2"))
            awaitReplayExecutors()
            assertEquals(2, fx.replayQueue.bufferDepth)
            assertEquals(0, fx.replayQueue.depth)

            fx.sut.onRemoteConfig()

            awaitCondition { fx.replayQueue.bufferDepth == 0 && fx.replayQueue.depth == 2 }
            assertEquals(0, fx.replayQueue.bufferDepth)
            assertEquals(2, fx.replayQueue.depth)
            assertTrue(fx.sut.isActive())
        } finally {
            fx.sut.uninstall()
        }
    }

    @Test
    fun `re-armed gate after reset drops the post-reset window when cached config is off`() {
        // Companion to the previous test: same re-arm sequence, opposite cached decision. The /flags
        // reload after reset must resolve the re-armed gate via the not-recordable rules — drop the
        // buffered post-reset window and stop the capturer.
        val fx = createIntegrationWithRealQueue(flagActive = true, hasFetched = true)
        val firstSessionId = UUID.randomUUID()
        val postHog = mock<PostHogInterface>()
        whenever(postHog.getSessionId()).thenReturn(firstSessionId)
        PostHogSessionManager.setSessionId(firstSessionId)
        fx.sut.install(postHog)
        fx.sut.start(resumeCurrent = true)
        try {
            fx.sut.onRemoteConfig()
            assertTrue(fx.sut.isActive())

            // reset()/identify(newUser): fetched latch cleared, cached flag now off, session rotates.
            whenever(fx.remoteConfig.hasRemoteConfigFetched()).thenReturn(false)
            whenever(fx.remoteConfig.isSessionReplayFlagActive()).thenReturn(false)
            val secondSessionId = UUID.randomUUID()
            whenever(postHog.getSessionId()).thenReturn(secondSessionId)
            PostHogSessionManager.setSessionId(secondSessionId)
            fx.sut.onSessionIdChanged()

            fx.replayQueue.add(createTestEvent("snapshot_after_reset_1"))
            fx.replayQueue.add(createTestEvent("snapshot_after_reset_2"))
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

    // Robolectric never runs the ViewRootImpl traversal that copies the window's
    // visibility into AttachInfo, so getWindowVisibility() stays GONE and the
    // integration treats the decor view as invisible. Set it directly.
    private fun makeWindowVisible(decorView: View) {
        val attachInfo =
            View::class.java.getDeclaredField("mAttachInfo")
                .apply { isAccessible = true }
                .get(decorView)
        attachInfo.javaClass.getDeclaredField("mWindowVisibility")
            .apply { isAccessible = true }
            .setInt(attachInfo, View.VISIBLE)
    }

    private fun screenshotFixture(): Pair<RealQueueFixture, PostHogFake> {
        val fx =
            createIntegrationWithRealQueue(
                flagActive = true,
                hasFetched = true,
                integrationContext = ApplicationProvider.getApplicationContext(),
            )
        fx.config.sessionReplayConfig.screenshot = true
        val fake = PostHogFake()
        fx.sut.install(fake)
        fx.sut.start(resumeCurrent = true)
        return fx to fake
    }

    @Test
    fun `screenshot mode skips the frame when the capture is discarded`() {
        // A window without a decor view makes PixelCopy.request throw, so the capture is
        // discarded (the same outcome as a PixelCopy failure or a redraw race in
        // production). No event may be emitted: an imageless "screenshot" wireframe is
        // rendered by the player as a placeholder tile, flashing until the next capture.
        val (fx, fake) = screenshotFixture()
        try {
            assertTrue(fx.sut.isActive())
            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            shadowOf(Looper.getMainLooper()).idle()
            val decorView = activity.window.decorView
            makeWindowVisible(decorView)
            fx.sut.decorViews[decorView] = ViewTreeSnapshotStatus(mock<NextDrawListener>())

            fx.sut.generateSnapshot(WeakReference(decorView), WeakReference(mock<Window>()))

            assertEquals(0, fake.captures)
        } finally {
            fx.sut.uninstall()
        }
    }

    @Test
    @Config(sdk = [26], shadows = [ShadowPixelCopy::class])
    fun `screenshot mode emits meta and full snapshot when the capture succeeds`() {
        // Control for the discarded-capture test: with PixelCopy succeeding, the same
        // pipeline must emit the snapshot event.
        val (fx, fake) = screenshotFixture()
        try {
            assertTrue(fx.sut.isActive())
            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            shadowOf(Looper.getMainLooper()).idle()
            val decorView = activity.window.decorView
            makeWindowVisible(decorView)
            fx.sut.decorViews[decorView] = ViewTreeSnapshotStatus(mock<NextDrawListener>())

            fx.sut.generateSnapshot(WeakReference(decorView), WeakReference(activity.window))

            assertEquals(1, fake.captures)
            assertEquals("\$snapshot", fake.event)
        } finally {
            fx.sut.uninstall()
        }
    }
}

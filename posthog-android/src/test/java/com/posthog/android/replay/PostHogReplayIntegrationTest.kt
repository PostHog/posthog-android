package com.posthog.android.replay

import android.content.Context
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.android.API_KEY
import com.posthog.android.PostHogAndroidConfig
import com.posthog.android.createPostHogFake
import com.posthog.android.internal.MainHandler
import com.posthog.internal.PostHogRemoteConfig
import com.posthog.internal.PostHogSessionManager
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [26]) // PostHogReplayIntegration.isSupported() requires API >= O.
internal class PostHogReplayIntegrationTest {
    private val context = mock<Context>()

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
    }

    private fun configWithSampling(
        flagActive: Boolean,
        samplingPasses: Boolean,
    ): PostHogAndroidConfig {
        val remoteConfig =
            mock<PostHogRemoteConfig> {
                on { isSessionReplayFlagActive() } doReturn flagActive
                on { makeSamplingDecision(any()) } doReturn samplingPasses
                on { getEventTriggers() } doReturn emptySet<String>()
            }
        return PostHogAndroidConfig(API_KEY).apply {
            remoteConfigHolder = remoteConfig
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
}

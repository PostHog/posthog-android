package com.posthog.android.replay

import android.content.Context
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.android.API_KEY
import com.posthog.android.PostHogAndroidConfig
import com.posthog.android.createPostHogFake
import com.posthog.android.internal.MainHandler
import com.posthog.internal.PostHogSessionManager
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

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

    private fun getSut(): PostHogReplayIntegration {
        val config = PostHogAndroidConfig(API_KEY)
        val mainHandler = MainHandler()
        return PostHogReplayIntegration(context, config, mainHandler)
    }

    @Test
    fun `onSessionIdChanged calls restartSessionReplay even when replay is inactive`() {
        // Even if a previous session was sampled out, the new session may now pass — so the
        // listener must drive into restartSessionReplay regardless of isSessionReplayActive.
        val sut = getSut()
        val fake = createPostHogFake()
        fake.sessionReplayActive = false
        sut.install(fake)
        try {
            PostHogSessionManager.startSession()
            sut.onSessionIdChanged()
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(1, fake.restartSessionReplayCalls)
        } finally {
            sut.uninstall()
        }
    }

    @Test
    fun `onSessionIdChanged calls restartSessionReplay when replay is active`() {
        val sut = getSut()
        val fake = createPostHogFake()
        fake.sessionReplayActive = true
        sut.install(fake)
        try {
            PostHogSessionManager.startSession()
            sut.onSessionIdChanged()
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(1, fake.restartSessionReplayCalls)
        } finally {
            sut.uninstall()
        }
    }

    @Test
    fun `onSessionIdChanged does not call restartSessionReplay when session is cleared`() {
        // peekSessionId returns null → there's nothing to record on; cleared-session branch
        // posts a stop() instead, never calls restartSessionReplay.
        val sut = getSut()
        val fake = createPostHogFake()
        fake.sessionReplayActive = true
        sut.install(fake)
        try {
            // No startSession — peekSessionId returns null.
            sut.onSessionIdChanged()
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(0, fake.restartSessionReplayCalls)
        } finally {
            sut.uninstall()
        }
    }
}

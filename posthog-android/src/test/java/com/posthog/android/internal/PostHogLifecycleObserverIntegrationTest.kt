package com.posthog.android.internal

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.PostHog
import com.posthog.android.API_KEY
import com.posthog.android.FakeLifecycle
import com.posthog.android.PostHogAndroidConfig
import com.posthog.android.createPostHogFake
import com.posthog.android.mockPackageInfo
import com.posthog.internal.PostHogDateProvider
import com.posthog.internal.PostHogDeviceDateProvider
import com.posthog.internal.PostHogSessionManager
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import java.util.Calendar
import java.util.Date
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

@RunWith(AndroidJUnit4::class)
internal class PostHogLifecycleObserverIntegrationTest {
    private val context = mock<Context>()
    private val fakeLifecycle = FakeLifecycle(Lifecycle.State.CREATED)

    private fun getSut(): PostHogLifecycleObserverIntegration {
        val config = PostHogAndroidConfig(API_KEY)
        val mainHandler = MainHandler()
        return PostHogLifecycleObserverIntegration(context, config, mainHandler, lifecycle = fakeLifecycle)
    }

    @BeforeTest
    fun `set up`() {
        PostHog.resetSharedInstance()
        PostHogSessionManager.endSession()
    }

    @AfterTest
    fun `tear down`() {
        PostHogSessionManager.isReactNative = false
        PostHogSessionManager.setDateProvider(PostHogDeviceDateProvider())
        PostHogSessionManager.endSession()
    }

    @Test
    fun `install adds the observer`() {
        val sut = getSut()

        val fake = createPostHogFake()

        sut.install(fake)

        assertEquals(1, fakeLifecycle.observers)

        sut.uninstall()
    }

    @Test
    fun `uninstall removes the observer`() {
        val sut = getSut()

        val fake = createPostHogFake()

        sut.install(fake)
        sut.uninstall()

        assertEquals(0, fakeLifecycle.observers)
    }

    @Test
    fun `onStart captures app opened - cold state`() {
        val sut = getSut()

        val fake = createPostHogFake()
        context.mockPackageInfo("1.0.0", 1)
        sut.install(fake)

        sut.onStart(ProcessLifecycleOwner.get())

        assertEquals("Application Opened", fake.event)
        assertEquals("1.0.0", fake.properties?.get("version"))
        assertEquals(1L, fake.properties?.get("build"))
        assertEquals(false, fake.properties?.get("from_background"))

        sut.uninstall()
    }

    @Test
    fun `onStart captures app opened - warm state`() {
        val sut = getSut()

        val fake = createPostHogFake()
        context.mockPackageInfo("1.0.0", 1)
        sut.install(fake)

        sut.onStart(ProcessLifecycleOwner.get())
        sut.onStart(ProcessLifecycleOwner.get())

        assertEquals("Application Opened", fake.event)
        assertEquals(true, fake.properties?.get("from_background"))

        sut.uninstall()
    }

    @Test
    fun `onStart captures app backgrounded`() {
        val sut = getSut()

        val fake = createPostHogFake()
        sut.install(fake)

        sut.onStart(ProcessLifecycleOwner.get())
        sut.onStop(ProcessLifecycleOwner.get())

        assertEquals("Application Backgrounded", fake.event)

        sut.uninstall()
    }

    @Test
    fun `onStop flushes the event queue`() {
        val sut = getSut()
        val fake = createPostHogFake()
        sut.install(fake)

        sut.onStart(ProcessLifecycleOwner.get())
        sut.onStop(ProcessLifecycleOwner.get())

        assertEquals(1, fake.flushes)

        sut.uninstall()
    }

    @Test
    fun `onStop flushes even when captureApplicationLifecycleEvents is disabled`() {
        val config =
            PostHogAndroidConfig(API_KEY).apply {
                captureApplicationLifecycleEvents = false
            }
        val mainHandler = MainHandler()
        val sut = PostHogLifecycleObserverIntegration(context, config, mainHandler, lifecycle = fakeLifecycle)
        val fake = createPostHogFake()
        sut.install(fake)

        sut.onStart(ProcessLifecycleOwner.get())
        sut.onStop(ProcessLifecycleOwner.get())

        assertEquals(1, fake.flushes)

        sut.uninstall()
    }

    @Test
    fun `onStart rotates session when session exceeds 24 hours`() {
        val baseTime = System.currentTimeMillis()
        val fakeDateProvider = FakeDateProviderForTest(baseTime)
        PostHogSessionManager.setDateProvider(fakeDateProvider)
        val config =
            PostHogAndroidConfig(API_KEY).apply {
                dateProvider = fakeDateProvider
                captureApplicationLifecycleEvents = false
            }
        val mainHandler = MainHandler()
        val sut = PostHogLifecycleObserverIntegration(context, config, mainHandler, lifecycle = fakeLifecycle)
        val fake = createPostHogFake()
        sut.install(fake)

        // Start a session (simulates first app open)
        PostHogSessionManager.startSession()
        val firstSessionId = PostHogSessionManager.getActiveSessionId()
        assertNotNull(firstSessionId)

        // First onStart at current time - this sets lastUpdatedSession
        sut.onStart(ProcessLifecycleOwner.get())

        // Simulate app going to background and coming back within 30 min interval
        // but the total session duration exceeds 24 hours.
        // We advance time by 25 minutes (within 30 min interval) repeatedly
        // to simulate many short background/foreground cycles over 24+ hours.
        // For the test, we just advance the clock by 24h+1min but keep lastUpdatedSession recent
        // by doing a stop/start cycle at 24h+1min - 10min, then at 24h+1min
        val twentyFourHoursMs = 1000L * 60 * 60 * 24
        val tenMinutesMs = 1000L * 60 * 10
        val oneMinuteMs = 1000L * 60

        // Advance to 24h - 10 min (session still under 24h, within 30 min interval doesn't matter
        // since we're simulating continuous use)
        fakeDateProvider.currentTimeMs = baseTime + twentyFourHoursMs - tenMinutesMs
        sut.onStop(ProcessLifecycleOwner.get())
        sut.onStart(ProcessLifecycleOwner.get()) // updates lastUpdatedSession

        // Now advance to 24h + 1 min (11 min after last update, within 30 min interval)
        // Session started at baseTime, so it's now > 24 hours old
        fakeDateProvider.currentTimeMs = baseTime + twentyFourHoursMs + oneMinuteMs
        sut.onStop(ProcessLifecycleOwner.get())
        sut.onStart(ProcessLifecycleOwner.get())

        // Session should have been rotated
        val secondSessionId = PostHogSessionManager.getActiveSessionId()
        assertNotNull(secondSessionId)
        assertNotEquals(firstSessionId, secondSessionId)

        sut.uninstall()
    }

    @Test
    fun `onStart restarts session replay after 24h rotation in background when replay was active`() {
        val baseTime = System.currentTimeMillis()
        val fakeDateProvider = FakeDateProviderForTest(baseTime)
        PostHogSessionManager.setDateProvider(fakeDateProvider)
        val config =
            PostHogAndroidConfig(API_KEY).apply {
                dateProvider = fakeDateProvider
                captureApplicationLifecycleEvents = false
            }
        val mainHandler = MainHandler()
        val sut = PostHogLifecycleObserverIntegration(context, config, mainHandler, lifecycle = fakeLifecycle)
        val fake = createPostHogFake()
        fake.sessionReplayActive = true
        sut.install(fake)

        PostHogSessionManager.startSession()
        val firstSessionId = PostHogSessionManager.getActiveSessionId()
        assertNotNull(firstSessionId)

        sut.onStart(ProcessLifecycleOwner.get())

        // Advance past 24h and background the app - triggers rotation in onStop
        val twentyFourHoursMs = 1000L * 60 * 60 * 24
        val oneMinuteMs = 1000L * 60
        fakeDateProvider.currentTimeMs = baseTime + twentyFourHoursMs + oneMinuteMs
        sut.onStop(ProcessLifecycleOwner.get())

        // After rotation in onStop: session ended, replay stopped
        assertEquals(1, fake.stopSessionReplayCalls)
        assertEquals(false, fake.sessionReplayActive)

        // User returns; a new session is created and replay should resume
        sut.onStart(ProcessLifecycleOwner.get())

        val secondSessionId = PostHogSessionManager.getActiveSessionId()
        assertNotNull(secondSessionId)
        assertNotEquals(firstSessionId, secondSessionId)
        assertEquals(1, fake.startSessionReplayCalls)
        assertEquals(true, fake.sessionReplayActive)

        sut.uninstall()
    }

    @Test
    fun `onStart does not restart session replay after 24h rotation when replay was inactive`() {
        val baseTime = System.currentTimeMillis()
        val fakeDateProvider = FakeDateProviderForTest(baseTime)
        PostHogSessionManager.setDateProvider(fakeDateProvider)
        val config =
            PostHogAndroidConfig(API_KEY).apply {
                dateProvider = fakeDateProvider
                captureApplicationLifecycleEvents = false
            }
        val mainHandler = MainHandler()
        val sut = PostHogLifecycleObserverIntegration(context, config, mainHandler, lifecycle = fakeLifecycle)
        val fake = createPostHogFake()
        // replay was never active
        sut.install(fake)

        PostHogSessionManager.startSession()
        sut.onStart(ProcessLifecycleOwner.get())

        val twentyFourHoursMs = 1000L * 60 * 60 * 24
        val oneMinuteMs = 1000L * 60
        fakeDateProvider.currentTimeMs = baseTime + twentyFourHoursMs + oneMinuteMs
        sut.onStop(ProcessLifecycleOwner.get())
        sut.onStart(ProcessLifecycleOwner.get())

        assertEquals(0, fake.startSessionReplayCalls)
        assertEquals(false, fake.sessionReplayActive)

        sut.uninstall()
    }

    @Test
    fun `onStart does not rotate session when session is under 24 hours`() {
        val baseTime = System.currentTimeMillis()
        val fakeDateProvider = FakeDateProviderForTest(baseTime)
        PostHogSessionManager.setDateProvider(fakeDateProvider)
        val config =
            PostHogAndroidConfig(API_KEY).apply {
                dateProvider = fakeDateProvider
                captureApplicationLifecycleEvents = false
            }
        val mainHandler = MainHandler()
        val sut = PostHogLifecycleObserverIntegration(context, config, mainHandler, lifecycle = fakeLifecycle)
        val fake = createPostHogFake()
        sut.install(fake)

        // Start a session
        PostHogSessionManager.startSession()
        val firstSessionId = PostHogSessionManager.getActiveSessionId()
        assertNotNull(firstSessionId)

        // First onStart
        sut.onStart(ProcessLifecycleOwner.get())

        // Simulate returning within 5 minutes (well within both 30 min and 24 hour limits)
        val fiveMinutesMs = 1000L * 60 * 5
        fakeDateProvider.currentTimeMs = baseTime + fiveMinutesMs

        sut.onStop(ProcessLifecycleOwner.get())
        sut.onStart(ProcessLifecycleOwner.get())

        // Session should NOT have been rotated
        val secondSessionId = PostHogSessionManager.getActiveSessionId()
        assertEquals(firstSessionId, secondSessionId)

        sut.uninstall()
    }

    @Test
    fun `onStart does not rotate session when React Native even if session exceeds 24 hours`() {
        PostHogSessionManager.isReactNative = true
        val baseTime = System.currentTimeMillis()
        val fakeDateProvider = FakeDateProviderForTest(baseTime)
        PostHogSessionManager.setDateProvider(fakeDateProvider)
        val config =
            PostHogAndroidConfig(API_KEY).apply {
                dateProvider = fakeDateProvider
                captureApplicationLifecycleEvents = false
            }
        val mainHandler = MainHandler()
        val sut = PostHogLifecycleObserverIntegration(context, config, mainHandler, lifecycle = fakeLifecycle)
        val fake = createPostHogFake()
        sut.install(fake)

        // RN sets its own session id
        val sessionId = java.util.UUID.randomUUID()
        PostHogSessionManager.setSessionId(sessionId)

        // First onStart
        sut.onStart(ProcessLifecycleOwner.get())

        // Advance past 24 hours
        val twentyFourHoursMs = 1000L * 60 * 60 * 24
        val oneMinuteMs = 1000L * 60
        fakeDateProvider.currentTimeMs = baseTime + twentyFourHoursMs + oneMinuteMs

        sut.onStop(ProcessLifecycleOwner.get())
        sut.onStart(ProcessLifecycleOwner.get())

        // Session should NOT have been rotated since RN manages its own session
        assertEquals(sessionId, PostHogSessionManager.getActiveSessionId())

        sut.uninstall()
    }

    /**
     * A simple fake date provider for testing time-dependent behavior.
     */
    private class FakeDateProviderForTest(initialTimeMs: Long = System.currentTimeMillis()) : PostHogDateProvider {
        var currentTimeMs: Long = initialTimeMs

        override fun currentDate(): Date = Date(currentTimeMs)

        override fun addSecondsToCurrentDate(seconds: Int): Date {
            val cal = Calendar.getInstance()
            cal.timeInMillis = currentTimeMs
            cal.add(Calendar.SECOND, seconds)
            return cal.time
        }

        override fun currentTimeMillis(): Long = currentTimeMs

        override fun nanoTime(): Long = System.nanoTime()
    }
}

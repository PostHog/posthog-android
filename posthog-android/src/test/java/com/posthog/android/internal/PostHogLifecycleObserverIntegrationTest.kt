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

        PostHogSessionManager.startSession()
        val firstSessionId = PostHogSessionManager.getActiveSessionId()
        assertNotNull(firstSessionId)

        sut.onStart(ProcessLifecycleOwner.get())

        val twentyFourHoursMs = 1000L * 60 * 60 * 24
        val tenMinutesMs = 1000L * 60 * 10
        val oneMinuteMs = 1000L * 60

        // Stop/start cycle at 24h-10m keeps lastUpdatedSession recent so the next
        // onStart routes through the 24h-rotation branch instead of the first-onStart branch.
        fakeDateProvider.currentTimeMs = baseTime + twentyFourHoursMs - tenMinutesMs
        sut.onStop(ProcessLifecycleOwner.get())
        sut.onStart(ProcessLifecycleOwner.get())

        fakeDateProvider.currentTimeMs = baseTime + twentyFourHoursMs + oneMinuteMs
        sut.onStop(ProcessLifecycleOwner.get())
        sut.onStart(ProcessLifecycleOwner.get())

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

        val twentyFourHoursMs = 1000L * 60 * 60 * 24
        val oneMinuteMs = 1000L * 60
        fakeDateProvider.currentTimeMs = baseTime + twentyFourHoursMs + oneMinuteMs
        sut.onStop(ProcessLifecycleOwner.get())

        assertEquals(1, fake.stopSessionReplayCalls)
        assertEquals(false, fake.sessionReplayActive)

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

        PostHogSessionManager.startSession()
        val firstSessionId = PostHogSessionManager.getActiveSessionId()
        assertNotNull(firstSessionId)

        sut.onStart(ProcessLifecycleOwner.get())

        val fiveMinutesMs = 1000L * 60 * 5
        fakeDateProvider.currentTimeMs = baseTime + fiveMinutesMs

        sut.onStop(ProcessLifecycleOwner.get())
        sut.onStart(ProcessLifecycleOwner.get())

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

        // RN owns its session id; the SDK must not rotate it even past the 24h cap.
        val sessionId = java.util.UUID.randomUUID()
        PostHogSessionManager.setSessionId(sessionId)

        sut.onStart(ProcessLifecycleOwner.get())

        val twentyFourHoursMs = 1000L * 60 * 60 * 24
        val oneMinuteMs = 1000L * 60
        fakeDateProvider.currentTimeMs = baseTime + twentyFourHoursMs + oneMinuteMs

        sut.onStop(ProcessLifecycleOwner.get())
        sut.onStart(ProcessLifecycleOwner.get())

        assertEquals(sessionId, PostHogSessionManager.getActiveSessionId())

        sut.uninstall()
    }

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

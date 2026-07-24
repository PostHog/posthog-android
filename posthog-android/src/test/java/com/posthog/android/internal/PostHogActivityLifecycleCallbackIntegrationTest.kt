package com.posthog.android.internal

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.PostHog
import com.posthog.PostHogFake
import com.posthog.android.API_KEY
import com.posthog.android.PostHogAndroidConfig
import com.posthog.android.createPostHogFake
import com.posthog.android.mockActivityUri
import com.posthog.android.mockScreenTitle
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
internal class PostHogActivityLifecycleCallbackIntegrationTest {
    private val application = mock<Application>()

    private fun getSut(
        captureDeepLinks: Boolean = true,
        captureScreenViews: Boolean = true,
        capturePushNotificationOpened: Boolean = true,
    ): PostHogActivityLifecycleCallbackIntegration {
        val config =
            PostHogAndroidConfig(API_KEY).apply {
                this.captureDeepLinks = captureDeepLinks
                this.captureScreenViews = captureScreenViews
                this.capturePushNotificationOpened = capturePushNotificationOpened
            }
        return PostHogActivityLifecycleCallbackIntegration(application, config)
    }

    private fun mockActivityWithExtras(vararg extras: Pair<String, String>): Activity {
        val activity = mock<Activity>()
        val intent =
            Intent().apply {
                extras.forEach { (key, value) -> putExtra(key, value) }
            }
        whenever(activity.intent).thenReturn(intent)
        return activity
    }

    @BeforeTest
    fun `set up`() {
        PostHog.resetSharedInstance()
    }

    @Test
    fun `install registers the lifecycle callback`() {
        val sut = getSut()

        val fake = createPostHogFake()

        sut.install(fake)

        verify(application).registerActivityLifecycleCallbacks(any())

        sut.uninstall()
    }

    @Test
    fun `uninstall unregisters the lifecycle callback`() {
        val sut = getSut()

        sut.uninstall()

        verify(application).unregisterActivityLifecycleCallbacks(any())
    }

    private fun executeDeepLinkTest(
        url: String,
        captureDeepLinks: Boolean = true,
    ): PostHogFake {
        val sut = getSut(captureDeepLinks = captureDeepLinks)
        val activity = mockActivityUri(url)

        val fake = createPostHogFake()

        sut.install(fake)
        sut.onActivityCreated(activity, null)
        sut.uninstall()

        return fake
    }

    private fun executeDeepLinkTestWithReferrer(
        url: String,
        captureDeepLinks: Boolean = true,
    ): PostHogFake {
        val sut = getSut(captureDeepLinks = captureDeepLinks)
        val activity = mockActivityUri(url, true)

        val fake = createPostHogFake()

        sut.install(fake)
        sut.onActivityCreated(activity, null)
        sut.uninstall()

        return fake
    }

    @Test
    fun `onActivityCreated captures deep link with url`() {
        val url = "http://google.com"
        val fake = executeDeepLinkTest(url)

        assertEquals("Deep Link Opened", fake.event)
        assertEquals(url, fake.properties?.get("url"))
    }

    @Test
    fun `onActivityCreated captures deep link with properties`() {
        val fake = executeDeepLinkTest("test://print?barcode=ABCDEFG&Reference=fasf")

        assertEquals("ABCDEFG", fake.properties?.get("barcode"))
        assertEquals("fasf", fake.properties?.get("Reference"))
    }

    @Test
    fun `onActivityCreated captures deep link even if hierarchical url`() {
        val url = "mailto:nobody@google.com"
        val fake = executeDeepLinkTest(url)

        assertEquals(url, fake.properties?.get("url"))
    }

    @Test
    fun `onActivityCreated captures deep link with web referrer`() {
        val url = "http://google.com"
        val domain = url.substringAfter("://")
        val fake = executeDeepLinkTestWithReferrer(url)

        assertEquals("Deep Link Opened", fake.event)
        assertEquals(url, fake.properties?.get("\$referrer"))
        assertEquals(domain, fake.properties?.get("\$referring_domain"))
    }

    @Test
    fun `onActivityCreated captures deep link with app referrer`() {
        val url = "android-app://com.example.source"
        val domain = url.substringAfter("://")
        val fake = executeDeepLinkTestWithReferrer(url)

        assertEquals("Deep Link Opened", fake.event)
        assertEquals(url, fake.properties?.get("\$referrer"))
        assertEquals(domain, fake.properties?.get("\$referring_domain"))
    }

    @Test
    fun `onActivityCreated also captures referrer for unparsable url`() {
        val url = "google.com"
        val fake = executeDeepLinkTestWithReferrer(url)

        assertEquals("Deep Link Opened", fake.event)
        assertEquals(url, fake.properties?.get("\$referrer"))
    }

    @Test
    fun `onActivityCreated does not capture deep link if disabled`() {
        val fake = executeDeepLinkTest("http://google.com", captureDeepLinks = false)

        assertNull(fake.event)
    }

    private fun executeCaptureScreenViewsTest(
        captureScreenViews: Boolean = true,
        throws: Boolean = false,
        title: String = "Title",
        activityName: String = "com.example.MyActivity",
        applicationLabel: String = "AppLabel",
    ): PostHogFake {
        val sut = getSut(captureScreenViews = captureScreenViews)
        val activity = mockScreenTitle(throws, title, activityName, applicationLabel)

        val fake = createPostHogFake()

        sut.install(fake)
        sut.onActivityStarted(activity)
        sut.uninstall()

        return fake
    }

    @Test
    fun `onActivityStarted captures captureScreenViews`() {
        val fake = executeCaptureScreenViewsTest()

        assertEquals("Title", fake.screenTitle)
    }

    @Test
    fun `onActivityStarted returns activityInfo name if labels are the same`() {
        val fake =
            executeCaptureScreenViewsTest(
                captureScreenViews = true,
                title = "AppLabel",
                activityName = "com.example.MyActivity",
                applicationLabel = "AppLabel",
            )
        assertEquals("MyActivity", fake.screenTitle)
    }

    @Test
    fun `onActivityStarted does not capture captureScreenViews if disabled`() {
        val fake = executeCaptureScreenViewsTest(false)

        assertNull(fake.screenTitle)
    }

    @Test
    fun `onActivityStarted does not capture if no title found`() {
        val fake = executeCaptureScreenViewsTest(throws = true)

        assertNull(fake.screenTitle)
    }

    @Test
    fun `onActivityStarted returns activity name if activity label are the empty`() {
        val fake = executeCaptureScreenViewsTest(title = "")

        assertEquals("MyActivity", fake.screenTitle)
    }

    @Test
    fun `onActivityCreated captures push notification opened from tray intent`() {
        val sut = getSut()
        val activity =
            mockActivityWithExtras(
                "google.message_id" to "m1",
                "posthog" to """{"campaign":"summer"}""",
            )
        val fake = createPostHogFake()

        sut.install(fake)
        sut.onActivityCreated(activity, null)
        sut.uninstall()

        assertEquals(1, fake.pushOpenedCaptures)
        assertNull(fake.pushOpenedTitle)
        assertNull(fake.pushOpenedBody)
        assertEquals("m1", fake.pushOpenedPayload?.get("google.message_id"))
        assertEquals("""{"campaign":"summer"}""", fake.pushOpenedPayload?.get("posthog"))
    }

    @Test
    fun `onActivityCreated dedups push notification by message id across recreations`() {
        val sut = getSut()
        val activity = mockActivityWithExtras("google.message_id" to "m1")
        val fake = createPostHogFake()

        sut.install(fake)
        sut.onActivityCreated(activity, null)
        sut.onActivityCreated(activity, null)
        sut.uninstall()

        assertEquals(1, fake.pushOpenedCaptures)
    }

    @Test
    fun `onActivityCreated does not re-capture push after process death restore`() {
        // A process kill resets the in-memory dedup, so a fresh integration models the restored process.
        // The redelivered launch intent arrives with non-null saved state, so it must not re-capture.
        val sut = getSut()
        val activity = mockActivityWithExtras("google.message_id" to "m1")
        val fake = createPostHogFake()

        sut.install(fake)
        sut.onActivityCreated(activity, Bundle())
        sut.uninstall()

        assertEquals(0, fake.pushOpenedCaptures)
    }

    @Test
    fun `onActivityCreated captures again for a new message id`() {
        val sut = getSut()
        val fake = createPostHogFake()

        sut.install(fake)
        sut.onActivityCreated(mockActivityWithExtras("google.message_id" to "m1"), null)
        sut.onActivityCreated(mockActivityWithExtras("google.message_id" to "m2"), null)
        sut.uninstall()

        assertEquals(2, fake.pushOpenedCaptures)
    }

    @Test
    fun `onActivityCreated does not capture push when no google message id`() {
        val sut = getSut()
        val activity = mockActivityWithExtras("some_key" to "value")
        val fake = createPostHogFake()

        sut.install(fake)
        sut.onActivityCreated(activity, null)
        sut.uninstall()

        assertEquals(0, fake.pushOpenedCaptures)
    }

    @Test
    fun `onActivityCreated does not capture push when disabled`() {
        val sut = getSut(capturePushNotificationOpened = false)
        val activity = mockActivityWithExtras("google.message_id" to "m1")
        val fake = createPostHogFake()

        sut.install(fake)
        sut.onActivityCreated(activity, null)
        sut.uninstall()

        assertEquals(0, fake.pushOpenedCaptures)
    }
}

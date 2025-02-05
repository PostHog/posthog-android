package com.posthog.android.internal

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.PostHog
import com.posthog.android.API_KEY
import com.posthog.android.PostHogAndroidConfig
import com.posthog.android.PostHogFake
import com.posthog.android.createPostHogFake
import com.posthog.android.mockActivityUri
import com.posthog.android.mockScreenTitle
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
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
    ): PostHogActivityLifecycleCallbackIntegration {
        val config =
            PostHogAndroidConfig(API_KEY).apply {
                this.captureDeepLinks = captureDeepLinks
                this.captureScreenViews = captureScreenViews
            }
        return PostHogActivityLifecycleCallbackIntegration(application, config)
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
}

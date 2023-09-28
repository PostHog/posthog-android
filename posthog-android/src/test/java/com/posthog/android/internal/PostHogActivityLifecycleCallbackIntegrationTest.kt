package com.posthog.android.internal

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.PostHog
import com.posthog.android.PostHogAndroidConfig
import com.posthog.android.PostHogFake
import com.posthog.android.apiKey
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
        val config = PostHogAndroidConfig(apiKey).apply {
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

        sut.install()

        verify(application).registerActivityLifecycleCallbacks(any())
    }

    @Test
    fun `uninstall unregisters the lifecycle callback`() {
        val sut = getSut()

        sut.uninstall()

        verify(application).unregisterActivityLifecycleCallbacks(any())
    }

    private fun executeDeepLinkTest(url: String, captureDeepLinks: Boolean = true): PostHogFake {
        val sut = getSut(captureDeepLinks = captureDeepLinks)
        val activity = mockActivityUri(url)

        val fake = createPostHogFake()

        sut.install()
        sut.onActivityCreated(activity, null)
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
    fun `onActivityCreated does not capture deep link if disabled`() {
        val fake = executeDeepLinkTest("http://google.com", captureDeepLinks = false)

        assertNull(fake.event)
    }

    private fun executeCaptureScreenViewsTest(captureScreenViews: Boolean = true, throws: Boolean = false): PostHogFake {
        val sut = getSut(captureScreenViews = captureScreenViews)
        val activity = mockScreenTitle(throws)

        val fake = createPostHogFake()

        sut.install()
        sut.onActivityStarted(activity)
        return fake
    }

    @Test
    fun `onActivityStarted captures captureScreenViews`() {
        val fake = executeCaptureScreenViewsTest()

        assertEquals("Title", fake.screenTitle)
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
}

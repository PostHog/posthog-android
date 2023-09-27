package com.posthog.android.internal

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.PostHog
import com.posthog.android.PostHogAndroidConfig
import com.posthog.android.PostHogFake
import com.posthog.android.apiKey
import com.posthog.android.createPostHogFake
import com.posthog.android.mockActivityUri
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
        captureApplicationLifecycleEvents: Boolean = true,
        captureDeepLinks: Boolean = true,
    ): PostHogActivityLifecycleCallbackIntegration {
        val config = PostHogAndroidConfig(apiKey).apply {
            this.captureApplicationLifecycleEvents = captureApplicationLifecycleEvents
            this.captureDeepLinks = captureDeepLinks
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

    @Test
    fun `onActivityCreated captures deep link with url`() {
        val sut = getSut()
        val url = "http://google.com"
        val activity = mockActivityUri(url)

        val fake = PostHogFake()
        PostHog.overrideSharedInstance(fake)

        sut.install()
        sut.onActivityCreated(activity, null)

        assertEquals("Deep Link Opened", fake.event)
        assertEquals(url, fake.properties?.get("url"))
    }

    @Test
    fun `onActivityCreated captures deep link with properties`() {
        val sut = getSut()
        val activity = mockActivityUri("test://print?barcode=ABCDEFG&Reference=fasf")

        val fake = createPostHogFake()

        sut.install()
        sut.onActivityCreated(activity, null)

        assertEquals("ABCDEFG", fake.properties?.get("barcode"))
        assertEquals("fasf", fake.properties?.get("Reference"))
    }

    @Test
    fun `onActivityCreated captures deep link even if hierarchical url`() {
        val sut = getSut()
        val url = "mailto:nobody@google.com"
        val activity = mockActivityUri(url)

        val fake = PostHogFake()
        PostHog.overrideSharedInstance(fake)

        sut.install()
        sut.onActivityCreated(activity, null)

        assertEquals(url, fake.properties?.get("url"))
    }

    @Test
    fun `onActivityCreated does not capture deep link if disabled`() {
        val sut = getSut(captureDeepLinks = false)
        val activity = mockActivityUri("http://google.com")

        val fake = createPostHogFake()

        sut.install()
        sut.onActivityCreated(activity, null)

        assertNull(fake.event)
    }
}

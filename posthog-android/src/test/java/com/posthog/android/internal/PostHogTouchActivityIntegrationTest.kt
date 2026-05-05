package com.posthog.android.internal

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.android.API_KEY
import com.posthog.android.PostHogAndroidConfig
import com.posthog.android.createPostHogFake
import org.junit.runner.RunWith
import kotlin.test.Test

@RunWith(AndroidJUnit4::class)
internal class PostHogTouchActivityIntegrationTest {
    private fun getSut(): PostHogTouchActivityIntegration {
        val config = PostHogAndroidConfig(API_KEY)
        return PostHogTouchActivityIntegration(config)
    }

    @Test
    fun `install and uninstall complete without throwing on a clean process`() {
        val sut = getSut()
        val fake = createPostHogFake()
        sut.install(fake)
        sut.uninstall()
    }

    @Test
    fun `double install is idempotent`() {
        val sut = getSut()
        val fake = createPostHogFake()
        sut.install(fake)
        sut.install(fake)
        sut.uninstall()
    }

    @Test
    fun `uninstall after install can be re-installed`() {
        val sut = getSut()
        val fake = createPostHogFake()
        sut.install(fake)
        sut.uninstall()
        sut.install(fake)
        sut.uninstall()
    }
}

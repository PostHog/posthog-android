package com.posthog.android

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.PostHog
import com.posthog.android.internal.PostHogActivityLifecycleCallbackIntegration
import com.posthog.android.internal.PostHogAndroidContext
import com.posthog.android.internal.PostHogAndroidLogger
import com.posthog.android.internal.PostHogAndroidNetworkStatus
import com.posthog.android.internal.PostHogAppInstallIntegration
import com.posthog.android.internal.PostHogLifecycleObserverIntegration
import com.posthog.android.internal.PostHogSharedPreferences
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
internal class PostHogAndroidTest {
    private val context = mock<Context>()

    @get:Rule
    val tmpDir = TemporaryFolder()

    @BeforeTest
    fun `set up`() {
        PostHog.close()
    }

    @AfterTest
    fun `set down`() {
        tmpDir.root.deleteRecursively()
    }

    @Test
    fun `sets Android Logger if System logger`() {
        val config = PostHogAndroidConfig(API_KEY)

        mockContextAppStart(context, tmpDir)

        PostHogAndroid.setup(context, config)

        assertTrue(config.logger is PostHogAndroidLogger)
    }

    @Test
    fun `sets Android context`() {
        val config = PostHogAndroidConfig(API_KEY)

        mockContextAppStart(context, tmpDir)

        PostHogAndroid.setup(context, config)

        assertTrue(config.context is PostHogAndroidContext)
    }

    @Test
    fun `sets legacy storage path`() {
        val config = PostHogAndroidConfig(API_KEY)

        mockContextAppStart(context, tmpDir)

        PostHogAndroid.setup(context, config)

        assertNotNull(config.legacyStoragePrefix)
    }

    @Test
    fun `sets storage path`() {
        val config = PostHogAndroidConfig(API_KEY)

        mockContextAppStart(context, tmpDir)

        PostHogAndroid.setup(context, config)

        assertNotNull(config.storagePrefix)
    }

    @Test
    fun `sets Android cache preferences`() {
        val config = PostHogAndroidConfig(API_KEY)

        mockContextAppStart(context, tmpDir)

        PostHogAndroid.setup(context, config)

        assertTrue(config.cachePreferences is PostHogSharedPreferences)
    }

    @Test
    fun `sets Android network checker`() {
        val config = PostHogAndroidConfig(API_KEY)

        mockContextAppStart(context, tmpDir)

        PostHogAndroid.setup(context, config)

        assertTrue(config.networkStatus is PostHogAndroidNetworkStatus)
    }

    @Test
    fun `sets Android SDK version`() {
        val config = PostHogAndroidConfig(API_KEY)

        mockContextAppStart(context, tmpDir)

        PostHogAndroid.setup(context, config)

        assertEquals(BuildConfig.VERSION_NAME, config.sdkVersion)
    }

    @Test
    fun `adds captureApplicationLifecycleEvents integrations`() {
        val config = PostHogAndroidConfig(API_KEY)

        mockContextAppStart(context, tmpDir)

        PostHogAndroid.setup(context, config)

        assertNotNull(
            config.integrations.find {
                it is PostHogAppInstallIntegration
            },
        )
        assertNotNull(
            config.integrations.find {
                it is PostHogLifecycleObserverIntegration
            },
        )
    }

    @Test
    fun `adds captureDeepLinks integration`() {
        val config =
            PostHogAndroidConfig(API_KEY).apply {
                captureScreenViews = false
            }

        mockContextAppStart(context, tmpDir)

        PostHogAndroid.setup(context, config)

        assertNotNull(
            config.integrations.find {
                it is PostHogActivityLifecycleCallbackIntegration
            },
        )
    }

    @Test
    fun `adds captureScreenViews integration`() {
        val config =
            PostHogAndroidConfig(API_KEY).apply {
                captureDeepLinks = false
            }

        mockContextAppStart(context, tmpDir)

        PostHogAndroid.setup(context, config)

        assertNotNull(
            config.integrations.find {
                it is PostHogActivityLifecycleCallbackIntegration
            },
        )
    }

    @Test
    fun `does not add captureDeepLinks, captureScreenViews and sessionReplay integration if disabled`() {
        val config =
            PostHogAndroidConfig(API_KEY).apply {
                captureDeepLinks = false
                captureScreenViews = false
                sessionReplay = false
            }

        mockContextAppStart(context, tmpDir)

        PostHogAndroid.setup(context, config)

        assertNull(
            config.integrations.find {
                it is PostHogActivityLifecycleCallbackIntegration
            },
        )
    }

    @Test
    fun `with creates new single instance`() {
        val config = PostHogAndroidConfig(API_KEY)

        mockContextAppStart(context, tmpDir)

        val postHog = PostHogAndroid.with(context, config)

        assertNotNull(postHog)

        postHog.close()
    }

    @Test
    fun `logger uses Logcat by default`() {
        val config = PostHogAndroidConfig(API_KEY)

        mockContextAppStart(context, tmpDir)

        val postHog = PostHogAndroid.with(context, config)

        assertTrue(config.logger is PostHogAndroidLogger)

        postHog.close()
    }
}

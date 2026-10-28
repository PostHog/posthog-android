package com.posthog.android.internal

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.OnCompleteListener
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import com.posthog.PostHog
import com.posthog.android.PROJECT_TOKEN
import com.posthog.android.PostHogAndroidConfig
import com.posthog.android.createPostHogFake
import com.posthog.internal.PostHogLogger
import org.junit.runner.RunWith
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs submitted tasks inline so install()'s async token fetch is deterministic in tests. */
private class SameThreadExecutorService : AbstractExecutorService() {
    override fun execute(command: Runnable) = command.run()

    override fun shutdown() {}

    override fun shutdownNow(): MutableList<Runnable> = mutableListOf()

    override fun isShutdown() = false

    override fun isTerminated() = false

    override fun awaitTermination(
        timeout: Long,
        unit: TimeUnit,
    ) = true
}

@RunWith(AndroidJUnit4::class)
internal class PostHogPushSubscriptionIntegrationTest {
    private class CapturingLogger : PostHogLogger {
        val messages = mutableListOf<String>()

        override fun log(message: String) {
            messages.add(message)
        }

        override fun isEnabled(): Boolean = true
    }

    @BeforeTest
    fun `set up`() {
        PostHog.resetSharedInstance()
    }

    @Test
    fun `install registers the fetched token and app id`() {
        val config = PostHogAndroidConfig(PROJECT_TOKEN)
        val fetcher = PushTokenFetcher { onToken -> onToken("fcm-token", "firebase-project") }
        val sut = PostHogPushSubscriptionIntegration(config, fetcher, SameThreadExecutorService())
        val fake = createPostHogFake()

        sut.install(fake)

        assertEquals(1, fake.pushRegistrations)
        assertEquals(1, fake.automaticPushRegistrations)
        assertEquals("fcm-token", fake.pushDeviceToken)
        assertEquals("firebase-project", fake.pushAppId)

        sut.uninstall()
    }

    @Test
    fun `install registers a Firebase Installation ID`() {
        val config = PostHogAndroidConfig(PROJECT_TOKEN)
        val fetcher = PushTokenFetcher { onToken -> onToken("firebase-fid", "firebase-project") }
        val sut = PostHogPushSubscriptionIntegration(config, fetcher, SameThreadExecutorService())
        val fake = createPostHogFake()

        sut.install(fake)

        assertEquals("firebase-fid", fake.pushDeviceToken)

        sut.uninstall()
    }

    @Test
    fun `a second install is a no-op while already installed`() {
        val config = PostHogAndroidConfig(PROJECT_TOKEN)
        var fetchCount = 0
        val fetcher =
            PushTokenFetcher { onToken ->
                fetchCount++
                onToken("fcm-token", "firebase-project")
            }
        val sut = PostHogPushSubscriptionIntegration(config, fetcher, SameThreadExecutorService())
        val fake = createPostHogFake()

        sut.install(fake)
        sut.install(fake)

        assertEquals(1, fetchCount)

        sut.uninstall()
    }

    @Test
    fun `onOptIn refetches the token and re-registers the device`() {
        val config = PostHogAndroidConfig(PROJECT_TOKEN)
        var fetchCount = 0
        val fetcher =
            PushTokenFetcher { onToken ->
                fetchCount++
                onToken("fcm-token", "firebase-project")
            }
        val sut = PostHogPushSubscriptionIntegration(config, fetcher, SameThreadExecutorService())
        val fake = createPostHogFake()

        sut.install(fake)
        assertEquals(1, fetchCount)
        assertEquals(1, fake.pushRegistrations)
        assertEquals(1, fake.automaticPushRegistrations)

        // A logout unregister would have cleared the token; opt-in must refetch and re-register.
        sut.onOptIn()

        assertEquals(2, fetchCount)
        assertEquals(2, fake.pushRegistrations)

        sut.uninstall()
    }

    @Test
    fun `install does not register when the fetcher yields no token`() {
        val config = PostHogAndroidConfig(PROJECT_TOKEN)
        val fetcher = PushTokenFetcher { /* no token available */ }
        val sut = PostHogPushSubscriptionIntegration(config, fetcher, SameThreadExecutorService())
        val fake = createPostHogFake()

        sut.install(fake)

        assertEquals(0, fake.pushRegistrations)

        sut.uninstall()
    }

    @Test
    fun `FirebasePushTokenFetcher skips and logs when Firebase is absent from the classpath`() {
        val logger = CapturingLogger()
        val config = PostHogAndroidConfig(PROJECT_TOKEN).apply { this.logger = logger }
        // firebase-messaging is a test dependency, so simulate absence via an unresolvable name.
        val fetcher = FirebasePushTokenFetcher(config, messagingClassName = "com.google.firebase.messaging.NotOnClasspath")

        var called = false
        fetcher.fetchToken { _, _ -> called = true }

        assertEquals(false, called)
        assertTrue(logger.messages.any { it.contains("Firebase Messaging not found") })
    }

    @Suppress("DEPRECATION")
    @Test
    fun `FirebasePushTokenFetcher fetches the token and project id when Firebase is present`() {
        val config = PostHogAndroidConfig(PROJECT_TOKEN)

        val options = mock<FirebaseOptions>()
        whenever(options.projectId).thenReturn("firebase-project")
        val firebaseApp = mock<FirebaseApp>()
        whenever(firebaseApp.options).thenReturn(options)
        whenever(firebaseApp.applicationContext).thenReturn(ApplicationProvider.getApplicationContext<Context>())

        val task = mock<Task<String>>()
        whenever(task.isSuccessful).thenReturn(true)
        whenever(task.result).thenReturn("fcm-token")
        whenever(task.addOnCompleteListener(any<OnCompleteListener<String>>())).thenAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            (invocation.arguments[0] as OnCompleteListener<String>).onComplete(task)
            task
        }
        val messaging = mock<FirebaseMessaging>()
        whenever(messaging.token).thenReturn(task)

        mockStatic(FirebaseApp::class.java).use { staticApp ->
            staticApp.`when`<FirebaseApp> { FirebaseApp.getInstance() }.thenReturn(firebaseApp)
            mockStatic(FirebaseMessaging::class.java).use { staticMessaging ->
                staticMessaging.`when`<FirebaseMessaging> { FirebaseMessaging.getInstance() }.thenReturn(messaging)

                var receivedToken: String? = null
                var receivedAppId: String? = null
                FirebasePushTokenFetcher(config).fetchToken { token, appId ->
                    receivedToken = token
                    receivedAppId = appId
                }

                assertEquals("fcm-token", receivedToken)
                assertEquals("firebase-project", receivedAppId)
            }
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun `FirebasePushTokenFetcher fetches the Firebase Installation ID when installation id registration is enabled`() {
        val config = PostHogAndroidConfig(PROJECT_TOKEN)
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName).applicationInfo!!.metaData =
            Bundle().apply { putBoolean("firebase_messaging_installation_id_enabled", true) }

        val options = mock<FirebaseOptions>()
        whenever(options.projectId).thenReturn("firebase-project")
        val firebaseApp = mock<FirebaseApp>()
        whenever(firebaseApp.options).thenReturn(options)
        whenever(firebaseApp.applicationContext).thenReturn(context)

        val task = mock<Task<String>>()
        whenever(task.isSuccessful).thenReturn(true)
        whenever(task.result).thenReturn("firebase-fid")
        whenever(task.addOnCompleteListener(any<OnCompleteListener<String>>())).thenAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            (invocation.arguments[0] as OnCompleteListener<String>).onComplete(task)
            task
        }
        val installations = mock<FirebaseInstallations>()
        whenever(installations.id).thenReturn(task)
        val messaging = mock<FirebaseMessaging>()

        mockStatic(FirebaseApp::class.java).use { staticApp ->
            staticApp.`when`<FirebaseApp> { FirebaseApp.getInstance() }.thenReturn(firebaseApp)
            mockStatic(FirebaseInstallations::class.java).use { staticInstallations ->
                staticInstallations.`when`<FirebaseInstallations> { FirebaseInstallations.getInstance(firebaseApp) }
                    .thenReturn(installations)
                mockStatic(FirebaseMessaging::class.java).use { staticMessaging ->
                    staticMessaging.`when`<FirebaseMessaging> { FirebaseMessaging.getInstance() }.thenReturn(messaging)

                    var receivedToken: String? = null
                    FirebasePushTokenFetcher(config).fetchToken { token, _ ->
                        receivedToken = token
                    }

                    assertEquals("firebase-fid", receivedToken)
                    // getToken() fails in this mode, so it must not be called.
                    verify(messaging, never()).token
                }
            }
        }
    }

    @Test
    fun `FirebasePushTokenFetcher skips when the Firebase project id is missing`() {
        val logger = CapturingLogger()
        val config = PostHogAndroidConfig(PROJECT_TOKEN).apply { this.logger = logger }

        val options = mock<FirebaseOptions>()
        whenever(options.projectId).thenReturn(null)
        val firebaseApp = mock<FirebaseApp>()
        whenever(firebaseApp.options).thenReturn(options)

        mockStatic(FirebaseApp::class.java).use { staticApp ->
            staticApp.`when`<FirebaseApp> { FirebaseApp.getInstance() }.thenReturn(firebaseApp)

            var called = false
            FirebasePushTokenFetcher(config).fetchToken { _, _ -> called = true }

            assertEquals(false, called)
            assertTrue(logger.messages.any { it.contains("project id is missing") })
        }
    }

    @Test
    fun `FirebasePushTokenFetcher skips when Firebase is not initialized`() {
        val logger = CapturingLogger()
        val config = PostHogAndroidConfig(PROJECT_TOKEN).apply { this.logger = logger }

        // No FirebaseApp.initializeApp ran in this process, so getInstance throws.
        var called = false
        FirebasePushTokenFetcher(config).fetchToken { _, _ -> called = true }

        assertEquals(false, called)
        assertTrue(logger.messages.any { it.contains("Firebase is not initialized") })
        assertNull(logger.messages.find { it.contains("Firebase Messaging not found") })
    }
}

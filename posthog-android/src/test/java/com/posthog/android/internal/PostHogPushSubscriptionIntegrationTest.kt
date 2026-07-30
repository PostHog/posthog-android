package com.posthog.android.internal

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.OnCompleteListener
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.posthog.PostHog
import com.posthog.android.API_KEY
import com.posthog.android.PostHogAndroidConfig
import com.posthog.android.createPostHogFake
import com.posthog.internal.PostHogLogger
import org.junit.runner.RunWith
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        val config = PostHogAndroidConfig(API_KEY)
        val fetcher = PushTokenFetcher { onToken -> onToken("fcm-token", "firebase-project") }
        val sut = PostHogPushSubscriptionIntegration(config, fetcher)
        val fake = createPostHogFake()

        sut.install(fake)

        assertEquals(1, fake.pushRegistrations)
        assertEquals("fcm-token", fake.pushDeviceToken)
        assertEquals("firebase-project", fake.pushAppId)

        sut.uninstall()
    }

    @Test
    fun `install does not register when the fetcher yields no token`() {
        val config = PostHogAndroidConfig(API_KEY)
        val fetcher = PushTokenFetcher { /* no token available */ }
        val sut = PostHogPushSubscriptionIntegration(config, fetcher)
        val fake = createPostHogFake()

        sut.install(fake)

        assertEquals(0, fake.pushRegistrations)

        sut.uninstall()
    }

    @Test
    fun `FirebasePushTokenFetcher skips and logs when Firebase is absent from the classpath`() {
        val logger = CapturingLogger()
        val config = PostHogAndroidConfig(API_KEY).apply { this.logger = logger }
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
        val config = PostHogAndroidConfig(API_KEY)

        val options = mock<FirebaseOptions>()
        whenever(options.projectId).thenReturn("firebase-project")
        val firebaseApp = mock<FirebaseApp>()
        whenever(firebaseApp.options).thenReturn(options)

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

    @Test
    fun `FirebasePushTokenFetcher skips when the Firebase project id is missing`() {
        val logger = CapturingLogger()
        val config = PostHogAndroidConfig(API_KEY).apply { this.logger = logger }

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
        val config = PostHogAndroidConfig(API_KEY).apply { this.logger = logger }

        // No FirebaseApp.initializeApp ran in this process, so getInstance throws.
        var called = false
        FirebasePushTokenFetcher(config).fetchToken { _, _ -> called = true }

        assertEquals(false, called)
        assertTrue(logger.messages.any { it.contains("Firebase is not initialized") })
        assertNull(logger.messages.find { it.contains("Firebase Messaging not found") })
    }
}

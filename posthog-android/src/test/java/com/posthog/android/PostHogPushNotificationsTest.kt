package com.posthog.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.PostHog
import org.junit.runner.RunWith
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
internal class PostHogPushNotificationsTest {
    private lateinit var fake: PostHogFake

    @BeforeTest
    fun `set up`() {
        PostHog.close()
        fake = createPostHogFake()
    }

    @AfterTest
    fun `set down`() {
        PostHog.resetSharedInstance()
    }

    @Test
    fun `registerToken delegates to PostHog with android platform`() {
        PostHogPushNotifications.registerToken(
            deviceToken = "fcm-token",
            firebaseProjectId = "firebase-project",
        )

        assertEquals(1, fake.pushRegistrations)
        assertEquals("fcm-token", fake.pushDeviceToken)
        assertEquals("firebase-project", fake.pushAppId)
        assertEquals("android", fake.pushPlatform)
    }
}

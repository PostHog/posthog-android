package com.posthog.android.internal

import com.posthog.android.PROJECT_TOKEN
import com.posthog.android.PostHogAndroidConfig
import com.posthog.internal.PostHogLogger
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class FirebasePushTokenFetcherWithoutFirebaseTest {
    @Test
    fun `fetching a token without Firebase skips registration`() {
        assumeTrue(java.lang.Boolean.getBoolean("posthog.test.noFirebase"))
        for (name in listOf("com.google.firebase.messaging.FirebaseMessaging", "com.google.android.gms.tasks.Task")) {
            assertFailsWith<ClassNotFoundException> { Class.forName(name) }
        }
        val messages = mutableListOf<String>()
        val config =
            PostHogAndroidConfig(PROJECT_TOKEN).apply {
                logger =
                    object : PostHogLogger {
                        override fun log(message: String) {
                            messages.add(message)
                        }

                        override fun isEnabled(): Boolean = true
                    }
            }
        var called = false

        FirebasePushTokenFetcher(config).fetchToken { _, _ -> called = true }

        assertFalse(called)
        assertEquals(1, messages.size)
        assertTrue(messages.single().contains("Firebase Messaging not found"))
    }
}

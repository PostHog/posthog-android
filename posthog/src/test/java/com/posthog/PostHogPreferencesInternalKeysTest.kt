package com.posthog

import com.posthog.internal.PostHogMemoryPreferences
import com.posthog.internal.PostHogPreferences.Companion.PUSH_OPENED_MESSAGE_IDS
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class PostHogPreferencesInternalKeysTest {
    @Test
    fun `the push dedupe id is internal storage, not a super property`() {
        val preferences = PostHogMemoryPreferences()
        preferences.setValue(PUSH_OPENED_MESSAGE_IDS, "0:1700000000%abcdef")
        preferences.setValue("aUserProperty", "kept")

        // getAll() feeds PostHog.buildProperties(), so anything not filtered here rides on every event.
        assertFalse(preferences.getAll().containsKey(PUSH_OPENED_MESSAGE_IDS))
        assertTrue(preferences.getAll().containsKey("aUserProperty"))
    }
}

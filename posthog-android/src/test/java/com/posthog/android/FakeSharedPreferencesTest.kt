package com.posthog.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class FakeSharedPreferencesTest {
    @Test
    fun `edits remain invisible until applied or committed`() {
        for (commit in listOf(false, true)) {
            val preferences = FakeSharedPreferences()
            val editor = preferences.edit().putString("key", "value")
            assertFalse(preferences.contains("key"))
            if (commit) assertTrue(editor.commit()) else editor.apply()
            assertEquals("value", preferences.getString("key", null))

            editor.remove("key")
            assertTrue(preferences.contains("key"))
            if (commit) assertTrue(editor.commit()) else editor.apply()
            assertFalse(preferences.contains("key"))
        }
    }

    @Test
    fun `clear precedes pending writes and does not change earlier snapshots`() {
        val preferences = FakeSharedPreferences()
        preferences.edit().putString("old", "value").apply()
        val snapshot = preferences.all
        val editor = preferences.edit().putInt("new", 42).clear()
        assertEquals<Map<String, *>>(mapOf("old" to "value"), preferences.all)
        editor.apply()
        assertEquals<Map<String, *>>(mapOf("new" to 42), preferences.all)
        assertEquals<Map<String, *>>(mapOf("old" to "value"), snapshot)

        preferences.edit().putString("new", null).apply()
        assertFalse(preferences.contains("new"))
    }
}

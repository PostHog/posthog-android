package com.posthog.android.internal

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.android.API_KEY
import com.posthog.android.FakeSharedPreferences
import com.posthog.android.PostHogAndroidConfig
import com.posthog.internal.PostHogPreferences.Companion.GROUPS
import com.posthog.internal.PostHogPreferences.Companion.STRINGIFIED_KEYS
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
internal class PostHogSharedPreferencesTests {
    private val context = mock<Context>()

    private fun getSut(): PostHogSharedPreferences {
        val config = PostHogAndroidConfig(API_KEY)
        return PostHogSharedPreferences(context, config, sharedPreferences = FakeSharedPreferences())
    }

    @Test
    fun `preferences set string`() {
        val sut = getSut()

        sut.setValue("key", "value")

        assertEquals("value", sut.getValue("key"))
    }

    @Test
    fun `preferences set boolean`() {
        val sut = getSut()

        sut.setValue("key", true)

        assertTrue(sut.getValue("key") as Boolean)
    }

    @Test
    fun `preferences set float`() {
        val sut = getSut()

        sut.setValue("key", 1f)

        assertEquals(1f, sut.getValue("key"))
    }

    @Test
    fun `preferences set long`() {
        val sut = getSut()

        sut.setValue("key", 1L)

        assertEquals(1L, sut.getValue("key"))
    }

    @Test
    fun `preferences set int`() {
        val sut = getSut()

        sut.setValue("key", 1)

        assertEquals(1, sut.getValue("key"))
    }

    @Test
    fun `preferences set string set`() {
        val sut = getSut()

        sut.setValue("key", setOf("1", "2"))

        assertEquals(setOf("1", "2"), sut.getValue("key"))
    }

    @Test
    fun `preferences set string list`() {
        val sut = getSut()

        sut.setValue("key", listOf("1", "2"))

        assertEquals(setOf("1", "2"), sut.getValue("key"))
    }

    @Test
    fun `preferences set string array`() {
        val sut = getSut()

        sut.setValue("key", arrayOf("1", "2"))

        assertEquals(setOf("1", "2"), sut.getValue("key"))
    }

    @Test
    fun `preferences stringify a non valid type`() {
        val sut = getSut()

        sut.setValue("key", Any())

        @Suppress("UNCHECKED_CAST")
        assertEquals(emptyMap(), sut.getValue("key") as? Map<String, Any>)
    }

    @Test
    fun `preferences deserialize groups`() {
        val sut = getSut()

        val props = mapOf("key" to "value")
        sut.setValue(GROUPS, props)

        assertEquals(props, sut.getValue(GROUPS))
    }

    @Test
    fun `preferences fallback to stringified version if not special and not stringified key`() {
        val sut = getSut()

        val props = mapOf("key" to "value")
        sut.setValue("myJson", props)

        // removing to make it testable
        sut.remove(STRINGIFIED_KEYS)

        val json = """{"key":"value"}"""
        assertEquals(json, sut.getValue("myJson"))
    }

    @Test
    fun `preferences clear all values`() {
        val sut = getSut()

        sut.setValue("key", "1")
        sut.clear()

        assertTrue(sut.getAll().isEmpty())
    }

    @Test
    fun `preferences clear all values but exceptions`() {
        val sut = getSut()

        sut.setValue("key", "1")
        sut.setValue("somethingElse", "1")
        sut.clear(except = listOf("key"))

        assertEquals("1", sut.getValue("key"))
        assertEquals(1, sut.getAll().size)
    }

    @Test
    fun `preferences clear keeps a preserved stringified value deserializable`() {
        val sut = getSut()

        // A Map value is serialized to JSON and tracked in STRINGIFIED_KEYS; when it survives a
        // partial clear it must still deserialize back to a Map ("sessionReplay" stands in for
        // SESSION_REPLAY, which is internal to the posthog module).
        val recordingConfig = mapOf("endpoint" to "/b/")
        sut.setValue("sessionReplay", recordingConfig)
        sut.setValue("scratch", mapOf("foo" to "bar"))

        sut.clear(except = listOf("sessionReplay"))

        assertEquals(recordingConfig, sut.getValue("sessionReplay"))
        assertNull(sut.getValue("scratch"))
    }

    @Test
    fun `clear without surviving stringified keys removes the STRINGIFIED_KEYS entry`() {
        val sut = getSut()

        sut.setValue("scratch", mapOf("foo" to "bar")) // serialized, tracked in STRINGIFIED_KEYS
        sut.setValue("plain", "keep-me") // a plain string, not stringified

        sut.clear(except = listOf("plain"))

        assertEquals("keep-me", sut.getValue("plain"))
        assertNull(sut.getValue("scratch"))
        // No stringified value survived, so the metadata entry must be removed entirely.
        assertNull(sut.getValue(STRINGIFIED_KEYS))
    }

    @Test
    fun `clear prunes STRINGIFIED_KEYS to only the surviving stringified keys`() {
        val sut = getSut()

        sut.setValue("keep", mapOf("a" to "1")) // serialized, survives
        sut.setValue("drop", mapOf("b" to "2")) // serialized, removed

        sut.clear(except = listOf("keep"))

        // Survivor still deserializes back to a Map; the dropped key is gone.
        assertEquals(mapOf("a" to "1"), sut.getValue("keep"))
        assertNull(sut.getValue("drop"))
        // STRINGIFIED_KEYS must list only the survivor, not a stale entry for the removed "drop".
        @Suppress("UNCHECKED_CAST")
        val stringifiedKeys = sut.getValue(STRINGIFIED_KEYS) as? Set<String>
        assertEquals(setOf("keep"), stringifiedKeys)
    }

    @Test
    fun `clear preserves a plain key and a stringified key without cross-contaminating STRINGIFIED_KEYS`() {
        val sut = getSut()

        // Mirrors the production reset() except-list: a plain string (like DEVICE_ID) alongside a
        // serialized map (like SESSION_REPLAY). The plain key must NOT leak into STRINGIFIED_KEYS,
        // or it would later be treated as JSON and corrupt on read.
        sut.setValue("device", "abc-123") // plain string, not stringified
        sut.setValue("sessionReplay", mapOf("endpoint" to "/b/")) // serialized, survives
        sut.setValue("drop", mapOf("x" to "1")) // serialized, removed

        sut.clear(except = listOf("device", "sessionReplay"))

        assertEquals("abc-123", sut.getValue("device"))
        assertEquals(mapOf("endpoint" to "/b/"), sut.getValue("sessionReplay"))
        assertNull(sut.getValue("drop"))
        @Suppress("UNCHECKED_CAST")
        val stringifiedKeys = sut.getValue(STRINGIFIED_KEYS) as? Set<String>
        assertEquals(setOf("sessionReplay"), stringifiedKeys)
    }

    @Test
    fun `preferences removes item`() {
        val sut = getSut()

        sut.setValue("key", "value")
        sut.remove("key")

        assertNull(sut.getValue("key"))
    }

    @Test
    fun `preferences returns all items`() {
        val sut = getSut()

        sut.setValue("key", "value")
        sut.setValue("somethingElse", "value")

        assertEquals("value", sut.getValue("key") as String)
        assertEquals("value", sut.getValue("somethingElse") as String)
        assertEquals(2, sut.getAll().size)
    }

    // Direct Boot: before the user first unlocks the device, credential encrypted storage is
    // unavailable and context.getSharedPreferences throws IllegalStateException.

    private class DirectBootContext {
        var locked = true
        val realPreferences = FakeSharedPreferences()
        val context =
            mock<Context> {
                on { getSharedPreferences(any(), any()) } doAnswer {
                    if (locked) {
                        throw IllegalStateException(
                            "SharedPreferences in credential encrypted storage are not available until after user (id 0) is unlocked",
                        )
                    }
                    realPreferences
                }
            }
    }

    private fun getDirectBootSut(directBootContext: DirectBootContext): PostHogSharedPreferences {
        val config = PostHogAndroidConfig(API_KEY)
        return PostHogSharedPreferences(directBootContext.context, config)
    }

    @Test
    fun `preferences do not crash while device is locked`() {
        val sut = getDirectBootSut(DirectBootContext())

        sut.setValue("key", "value")

        assertEquals("value", sut.getValue("key"))
        assertEquals(mapOf<String, Any>("key" to "value"), sut.getAll())
    }

    @Test
    fun `preferences remove and clear pending writes while device is locked`() {
        val sut = getDirectBootSut(DirectBootContext())

        sut.setValue("removed", "value")
        sut.remove("removed")
        sut.setValue("cleared", "value")
        sut.setValue("kept", "value")
        sut.clear(except = listOf("kept"))

        assertNull(sut.getValue("removed"))
        assertNull(sut.getValue("cleared"))
        assertEquals("value", sut.getValue("kept"))
    }

    @Test
    fun `preferences flush pending writes once the device is unlocked`() {
        val directBootContext = DirectBootContext()
        val sut = getDirectBootSut(directBootContext)

        sut.setValue("key", "value")
        directBootContext.locked = false

        assertEquals("value", sut.getValue("key"))
        assertEquals("value", directBootContext.realPreferences.getString("key", null))
    }

    @Test
    fun `remove while locked also removes a previously persisted value after unlock`() {
        val directBootContext = DirectBootContext()
        directBootContext.realPreferences.edit().putString("persisted", "old").apply()
        val sut = getDirectBootSut(directBootContext)

        sut.remove("persisted")
        directBootContext.locked = false

        assertNull(sut.getValue("persisted"))
        assertNull(directBootContext.realPreferences.getString("persisted", null))
    }

    @Test
    fun `clear while locked also clears previously persisted values after unlock`() {
        val directBootContext = DirectBootContext()
        directBootContext.realPreferences.edit()
            .putString("persisted", "old")
            .putString("kept", "old")
            .apply()
        val sut = getDirectBootSut(directBootContext)

        sut.clear(except = listOf("kept"))
        directBootContext.locked = false

        assertNull(sut.getValue("persisted"))
        assertEquals("old", sut.getValue("kept"))
        assertNull(directBootContext.realPreferences.getString("persisted", null))
    }

    @Test
    fun `set after remove while locked keeps the new value after unlock`() {
        val directBootContext = DirectBootContext()
        directBootContext.realPreferences.edit().putString("key", "old").apply()
        val sut = getDirectBootSut(directBootContext)

        sut.remove("key")
        sut.setValue("key", "new")
        directBootContext.locked = false

        assertEquals("new", sut.getValue("key"))
        assertEquals("new", directBootContext.realPreferences.getString("key", null))
    }
}

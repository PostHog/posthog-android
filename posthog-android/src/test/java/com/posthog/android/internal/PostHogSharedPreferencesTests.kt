package com.posthog.android.internal

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.android.API_KEY
import com.posthog.android.FakeSharedPreferences
import com.posthog.android.PostHogAndroidConfig
import com.posthog.internal.PostHogPreferences.Companion.GROUPS
import com.posthog.internal.PostHogPreferences.Companion.STRINGIFIED_KEYS
import org.junit.runner.RunWith
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
}

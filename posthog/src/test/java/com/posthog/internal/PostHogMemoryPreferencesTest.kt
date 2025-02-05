package com.posthog.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PostHogMemoryPreferencesTest {
    private fun getSut(): PostHogMemoryPreferences {
        return PostHogMemoryPreferences()
    }

    @Test
    fun `preferences set string`() {
        val sut = getSut()

        sut.setValue("key", "value")

        assertEquals("value", sut.getValue("key"))
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

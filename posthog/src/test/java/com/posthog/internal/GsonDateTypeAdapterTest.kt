package com.posthog.internal

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.posthog.API_KEY
import com.posthog.PostHogConfig
import java.io.File
import java.time.OffsetDateTime
import java.util.Date
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class GsonDateTypeAdapterTest {
    private val config = PostHogConfig(API_KEY)
    private val gsonFakeDateType = object : TypeToken<FakeDate>() {}.type

    @Suppress("DEPRECATION")
    private fun getSut(): Gson {
        return GsonBuilder().apply {
            registerTypeAdapter(Date::class.java, GsonDateTypeAdapter(config))
                .setLenient()
        }.create()
    }

    @Test
    fun `deserializes json to date`() {
        val sut = getSut()

        val file = File("src/test/resources/json/valid-date.json")

        val fakeDate = sut.fromJson<FakeDate>(file.readText(), gsonFakeDateType)
        val date = parseISO8601Date("2023-09-20T11:58:49.000Z")!!

        assertTrue(date.compareTo(fakeDate.date) == 0)
    }

    @Test
    fun `deserialize swallow exception if broken date`() {
        val sut = getSut()

        val file = File("src/test/resources/json/broken-date.json")

        val fakeDate = sut.fromJson<FakeDate>(file.readText(), gsonFakeDateType)

        assertNull(fakeDate.date)
    }

    @Test
    fun `serializes date to json`() {
        val sut = getSut()

        val date = parseISO8601Date("2023-09-20T11:58:49.000Z")!!
        val fakeDate = FakeDate(date)

        val json = sut.toJson(fakeDate)
        val expectedJson = """{"date":"2023-09-20T11:58:49.000Z"}"""

        assertEquals(expectedJson, json)
    }

    @Test
    fun `serializes date as the equivalent UTC instant outside UTC default timezone`() {
        val originalTimeZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            val sut = getSut()
            val instant = OffsetDateTime.parse("2023-07-15T08:30:45.123-07:00").toInstant()
            val date = Date.from(instant)

            val json = sut.toJson(FakeDate(date))

            assertEquals("""{"date":"2023-07-15T15:30:45.123Z"}""", json)
            assertEquals(instant.toEpochMilli(), date.time)
        } finally {
            TimeZone.setDefault(originalTimeZone)
        }
    }
}

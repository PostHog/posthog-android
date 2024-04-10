package com.posthog.internal

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.internal.bind.util.ISO8601Utils
import com.google.gson.reflect.TypeToken
import com.posthog.API_KEY
import com.posthog.PostHogConfig
import java.io.File
import java.text.ParsePosition
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class GsonDateTypeAdapterTest {
    private val config = PostHogConfig(API_KEY)
    private val gsonFakeDateType = object : TypeToken<FakeDate>() {}.type

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
        val date = ISO8601Utils.parse("2023-09-20T11:58:49.000Z", ParsePosition(0))

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

        val date = ISO8601Utils.parse("2023-09-20T11:58:49.000Z", ParsePosition(0))
        val fakeDate = FakeDate(date)

        val json = sut.toJson(fakeDate)
        val expectedJson = """{"date":"2023-09-20T11:58:49.000Z"}"""

        assertEquals(expectedJson, json)
    }
}

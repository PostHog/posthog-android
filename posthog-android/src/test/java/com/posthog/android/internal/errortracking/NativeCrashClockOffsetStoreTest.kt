package com.posthog.android.internal.errortracking

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
internal class NativeCrashClockOffsetStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = NativeCrashClockOffsetStore(context)

    @Test
    fun `returns null before any run is recorded`() {
        assertNull(store.offsetAt(1_000))
    }

    @Test
    fun `returns the offset of the latest run started at or before the time`() {
        store.record(runStartWallClockMs = 100, offsetMs = -1)
        store.record(runStartWallClockMs = 300, offsetMs = -3)
        store.record(runStartWallClockMs = 200, offsetMs = -2)

        assertNull(store.offsetAt(99))
        assertEquals(-1, store.offsetAt(100))
        assertEquals(-2, store.offsetAt(250))
        assertEquals(-3, store.offsetAt(10_000))
    }

    @Test
    fun `keeps only the most recent runs`() {
        (1L..20L).forEach { store.record(runStartWallClockMs = it * 100, offsetMs = -it) }

        assertNull(store.offsetAt(400))
        assertEquals(-5, store.offsetAt(500))
        assertEquals(-20, store.offsetAt(2_000))
    }
}

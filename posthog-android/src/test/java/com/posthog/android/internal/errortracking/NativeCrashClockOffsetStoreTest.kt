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
    fun `returns null for a pid with no recorded run`() {
        assertNull(store.offsetFor(1))
    }

    @Test
    fun `returns the offset recorded by each pid`() {
        store.record(pid = 1, offsetMs = -1)
        store.record(pid = 2, offsetMs = -2)

        assertEquals(-1, store.offsetFor(1))
        assertEquals(-2, store.offsetFor(2))
    }

    @Test
    fun `a later record for the same pid replaces the earlier one`() {
        store.record(pid = 1, offsetMs = -1)
        store.record(pid = 1, offsetMs = 0)

        assertEquals(0, store.offsetFor(1))
    }

    @Test
    fun `keeps only the most recent runs`() {
        (1..20).forEach { store.record(pid = it, offsetMs = -it.toLong()) }

        assertNull(store.offsetFor(4))
        assertEquals(-5, store.offsetFor(5))
        assertEquals(-20, store.offsetFor(20))
    }
}

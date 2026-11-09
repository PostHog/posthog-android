package com.posthog.android.internal.errortracking

import android.content.Context
import android.content.SharedPreferences
import com.posthog.android.FakeSharedPreferences
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class NativeCrashOptOutIntervalStoreTest {
    private val sharedPreferences = FakeSharedPreferences()
    private val context =
        mock<Context> {
            on { getSharedPreferences(anyOrNull(), any()) } doReturn sharedPreferences
        }

    private fun NativeCrashOptOutIntervalStore.covers(
        timestampMs: Long,
        nowMs: Long,
    ): Boolean = intervals(nowMs).any { timestampMs in it }

    @Test
    fun `an open interval persists across instances and extends to now`() {
        NativeCrashOptOutIntervalStore(context).open(100L)

        val store = NativeCrashOptOutIntervalStore(context)
        assertTrue(store.covers(100L, nowMs = 500L))
        assertTrue(store.covers(500L, nowMs = 500L))
        assertFalse(store.covers(501L, nowMs = 500L))
    }

    @Test
    fun `a timestamp before the open start is not contained`() {
        val store = NativeCrashOptOutIntervalStore(context)
        store.open(100L)

        assertFalse(store.covers(99L, nowMs = 500L))
    }

    @Test
    fun `a second open keeps the earliest start`() {
        val store = NativeCrashOptOutIntervalStore(context)
        store.open(100L)
        store.open(200L)

        assertTrue(store.covers(150L, nowMs = 500L))
    }

    @Test
    fun `close moves the open interval to the closed list`() {
        val store = NativeCrashOptOutIntervalStore(context)
        store.open(100L)
        store.close(200L)

        assertTrue(store.covers(100L, nowMs = 1000L))
        assertTrue(store.covers(200L, nowMs = 1000L))
        assertFalse(store.covers(201L, nowMs = 1000L))

        store.close(300L)
        assertFalse(store.covers(250L, nowMs = 1000L))
    }

    @Test
    fun `contains is inclusive on both bounds of closed intervals`() {
        val store = NativeCrashOptOutIntervalStore(context)
        store.open(100L)
        store.close(200L)
        store.open(300L)
        store.close(400L)

        assertFalse(store.covers(99L, nowMs = 1000L))
        assertTrue(store.covers(100L, nowMs = 1000L))
        assertTrue(store.covers(200L, nowMs = 1000L))
        assertFalse(store.covers(250L, nowMs = 1000L))
        assertTrue(store.covers(300L, nowMs = 1000L))
        assertTrue(store.covers(400L, nowMs = 1000L))
        assertFalse(store.covers(401L, nowMs = 1000L))
    }

    @Test
    fun `prune drops intervals ending at or before the watermark and keeps later ones`() {
        val store = NativeCrashOptOutIntervalStore(context)
        store.open(100L)
        store.close(200L)
        store.open(300L)
        store.close(400L)

        store.prune(200L)

        assertFalse(store.covers(150L, nowMs = 1000L))
        assertTrue(store.covers(350L, nowMs = 1000L))
    }

    @Test
    fun `malformed entries are ignored`() {
        sharedPreferences.edit().putString("optOutIntervals", "garbage;100:200;x:1;").apply()

        val store = NativeCrashOptOutIntervalStore(context)

        assertTrue(store.covers(150L, nowMs = 1000L))
        assertFalse(store.covers(250L, nowMs = 1000L))
    }

    @Test
    fun `a closed interval whose clock moved back still covers the span`() {
        val store = NativeCrashOptOutIntervalStore(context)
        store.open(36_000_000L)
        store.close(35_100_000L)

        assertTrue(store.covers(35_500_000L, nowMs = 40_000_000L))
        assertFalse(store.covers(36_000_001L, nowMs = 40_000_000L))
    }

    @Test
    fun `an open interval still covers the span when now is before its start`() {
        val store = NativeCrashOptOutIntervalStore(context)
        sharedPreferences.edit().putLong("optOutSince", 36_000_000L).apply()

        assertTrue(store.covers(35_500_000L, nowMs = 35_100_000L))
    }

    @Test
    fun `an open with an earlier now re-stamps the start`() {
        val store = NativeCrashOptOutIntervalStore(context)
        store.open(36_000_000L)
        store.open(34_000_000L)
        store.close(35_000_000L)

        assertTrue(store.covers(34_000_000L, nowMs = 40_000_000L))
        assertTrue(store.covers(35_500_000L, nowMs = 40_000_000L))
        assertFalse(store.covers(33_999_999L, nowMs = 40_000_000L))
    }

    @Test
    fun `open commits so a crash before the flush keeps the interval, close and prune apply`() {
        val editor = mock<SharedPreferences.Editor>()
        val preferences = mock<SharedPreferences>()
        whenever(preferences.edit()).thenReturn(editor)
        whenever(editor.putLong(any(), any())).thenReturn(editor)
        whenever(editor.putString(any(), anyOrNull())).thenReturn(editor)
        whenever(editor.remove(any())).thenReturn(editor)
        whenever(context.getSharedPreferences(anyOrNull(), any())).thenReturn(preferences)

        NativeCrashOptOutIntervalStore(context).open(100L)
        verify(editor).putLong("optOutSince", 100L)
        verify(editor).commit()

        whenever(preferences.contains("optOutSince")).thenReturn(true)
        whenever(preferences.getLong(any(), any())).thenReturn(100L)
        NativeCrashOptOutIntervalStore(context).close(200L)
        verify(editor).putString("optOutIntervals", "100:200")
        verify(editor).remove("optOutSince")

        verify(editor, times(1)).apply()
        verify(editor, times(1)).commit()
    }
}

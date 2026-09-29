package com.posthog.android.internal.errortracking

import android.content.Context
import android.content.SharedPreferences
import com.posthog.android.FakeSharedPreferences
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.Test
import kotlin.test.assertEquals

internal class NativeCrashWatermarkStoreTest {
    private val sharedPreferences = FakeSharedPreferences()
    private val context =
        mock<Context> {
            on { getSharedPreferences(anyOrNull(), any()) } doReturn sharedPreferences
        }

    @Test
    fun `acknowledgement commits synchronously instead of applying asynchronously`() {
        val editor = mock<SharedPreferences.Editor>()
        val preferences = mock<SharedPreferences>()
        whenever(preferences.edit()).thenReturn(editor)
        whenever(editor.putLong(any(), any())).thenReturn(editor)
        whenever(context.getSharedPreferences(anyOrNull(), any())).thenReturn(preferences)

        NativeCrashWatermarkStore(context).advance(1234L)

        verify(editor).putLong("lastCapturedTimestamp", 1234L)
        verify(editor).commit()
        verify(editor, never()).apply()
    }

    @Test
    fun `starts at zero and persists advances across instances`() {
        val store = NativeCrashWatermarkStore(context)
        assertEquals(0L, store.get())

        store.advance(1234L)

        assertEquals(1234L, store.get())
        // crash dedup across launches relies on a fresh instance reading the
        // persisted value
        assertEquals(1234L, NativeCrashWatermarkStore(context).get())
    }
}

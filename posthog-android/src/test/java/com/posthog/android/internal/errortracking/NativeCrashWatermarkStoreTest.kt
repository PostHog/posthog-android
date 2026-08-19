package com.posthog.android.internal.errortracking

import android.content.Context
import com.posthog.android.FakeSharedPreferences
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import kotlin.test.Test
import kotlin.test.assertEquals

internal class NativeCrashWatermarkStoreTest {
    private val sharedPreferences = FakeSharedPreferences()
    private val context =
        mock<Context> {
            on { getSharedPreferences(anyOrNull(), any()) } doReturn sharedPreferences
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

package com.posthog.android.replay

import com.posthog.android.replay.internal.WindowDrawState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class WindowDrawStateCaptureSchedulingTest {
    @Test
    fun `busy requests coalesce into one wakeup after the scheduling gate opens`() {
        val state = WindowDrawState()
        var wakeups = 0
        assertTrue(state.tryScheduleCapture())
        repeat(100) {
            assertFalse(
                state.tryScheduleCapture {
                    wakeups++
                    assertTrue(state.tryScheduleCapture())
                },
            )
        }
        // A caller without a wakeup must not clear another caller's pending request.
        assertFalse(state.tryScheduleCapture())
        assertEquals(0, wakeups)
        state.finishScheduledCapture()
        assertEquals(1, wakeups)
        state.finishScheduledCapture()
        assertEquals(1, wakeups)
    }

    @Test
    fun `pixel copy completion does not wake a request while its worker still owns the gate`() {
        val state = WindowDrawState()
        var wakeups = 0
        assertTrue(state.tryScheduleCapture())
        state.beginPixelCopy()
        assertFalse(state.tryScheduleCapture { wakeups++ })
        state.finishPixelCopy()
        assertEquals(0, wakeups)
        state.finishScheduledCapture()
        assertEquals(1, wakeups)
        assertTrue(state.tryScheduleCapture())
    }

    @Test
    fun `timed out pixel copy keeps the wakeup pending until the callback returns`() {
        val state = WindowDrawState()
        var wakeups = 0
        assertTrue(state.tryScheduleCapture())
        state.beginPixelCopy()
        assertFalse(state.tryScheduleCapture { wakeups++ })
        state.finishScheduledCapture()
        assertEquals(0, wakeups)
        assertFalse(state.tryScheduleCapture())
        state.finishPixelCopy()
        assertEquals(1, wakeups)
        assertTrue(state.tryScheduleCapture())
    }

    @Test
    fun `a request arriving after the worker timeout is woken by pixel copy completion`() {
        val state = WindowDrawState()
        var wakeups = 0
        assertTrue(state.tryScheduleCapture())
        state.beginPixelCopy()
        state.finishScheduledCapture()
        assertFalse(state.tryScheduleCapture { wakeups++ })
        assertEquals(0, wakeups)
        state.finishPixelCopy()
        assertEquals(1, wakeups)
        state.finishPixelCopy()
        assertEquals(1, wakeups)
    }
}

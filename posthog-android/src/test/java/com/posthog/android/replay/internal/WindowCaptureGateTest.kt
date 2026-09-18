package com.posthog.android.replay.internal

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class WindowCaptureGateTest {
    @Test
    fun `callback completion does not release a running worker`() {
        val state = WindowDrawState()
        assertTrue(state.tryScheduleCapture())
        state.beginPixelCopy()
        state.finishPixelCopy()
        assertFalse(state.tryScheduleCapture())
        state.finishScheduledCapture()
        assertTrue(state.tryScheduleCapture())
    }

    @Test
    fun `worker timeout does not release an in flight callback`() {
        val state = WindowDrawState()
        assertTrue(state.tryScheduleCapture())
        state.beginPixelCopy()
        state.finishScheduledCapture()
        assertFalse(state.tryScheduleCapture())
        state.finishPixelCopy()
        assertTrue(state.tryScheduleCapture())
    }

    @Test
    fun `snapshot and mask resets do not release outstanding work`() {
        val state = WindowDrawState()
        assertTrue(state.tryScheduleCapture())
        state.reset()
        state.resetSnapshotState()
        state.invalidateMaskCapture()
        assertFalse(state.tryScheduleCapture())

        state.beginPixelCopy()
        state.finishScheduledCapture()
        state.resetSnapshotState()
        state.finishLegacyCapture()
        assertFalse(state.tryScheduleCapture())
        state.finishPixelCopy()
        assertTrue(state.tryScheduleCapture())
    }
}

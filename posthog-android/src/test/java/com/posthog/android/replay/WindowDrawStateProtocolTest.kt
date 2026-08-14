package com.posthog.android.replay

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.android.replay.internal.BaselineResult
import com.posthog.android.replay.internal.WindowDrawState
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bounded model check of the real [WindowDrawState] keep/discard protocol.
 *
 * Every mutating protocol operation is serialized (captureLock, or single-writer volatile for
 * recordDraw), so a concurrent execution is equivalent to some sequential interleaving. This
 * test enumerates EVERY placement of up to three main-thread events (geometry mutation, draw,
 * layout) across all five gaps of the capture pipeline (before arming, arming->pre-walk,
 * pre-walk->baseline, baseline->post-walk, post-walk->finish) and checks each schedule's
 * verdict against the safety and liveness contracts.
 *
 * Scope: each draw's ops (recordDraw, beginDrawSample, recordMaskWalk) run atomically within a
 * gap. Interleavings that split one draw across a capture-thread op collapse to an enumerated
 * placement, except a sample straddling finishMaskCapture, which can only discard more (fail
 * closed) and is covered by a dedicated test [a draw sample still open at finish discards the
 * capture]. Mask geometry is one rect; the ABA case (geometry moves and returns) needs
 * repeated values this enumeration doesn't emit and is covered by the "returns to its starting
 * position" test in PostHogReplayIntegrationTest. The trust model matches production: geometry
 * reaches PixelCopy only through a draw, and every draw invokes the OnDrawListener.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [26])
internal class WindowDrawStateProtocolTest {
    private enum class Event { MUTATE, DRAW, LAYOUT }

    // Gap 0: before beginMaskCapture. 1: before the pre-walk reads geometry. 2: before
    // setBaseline. 3: before the post-walk reads geometry (the PixelCopy window). 4: before
    // finishMaskCapture.
    private class Schedule(val eventsPerGap: List<List<Event>>) {
        override fun toString(): String = eventsPerGap.toString()
    }

    private class Outcome(
        val kept: Boolean,
        // Geometry of every draw that committed while the capture was armed.
        val drawGeometries: List<Int>,
        val baselineGeometry: Int,
        val laidOut: Boolean,
        val drewDuringPreWalk: Boolean,
    )

    private fun rectsFor(geometry: Int): List<Rect> = listOf(Rect(geometry, 0, geometry + 1, 1))

    private fun run(schedule: Schedule): Outcome {
        val drawState = WindowDrawState()
        var geometry = 0
        var mutations = 0
        val drawGeometries = mutableListOf<Int>()
        var laidOut = false

        fun playGap(
            gap: Int,
            armed: Boolean,
        ) {
            for (event in schedule.eventsPerGap[gap]) {
                when (event) {
                    Event.MUTATE -> geometry = ++mutations
                    Event.LAYOUT -> {
                        drawState.recordLayout()
                        // Layouts before reset() are out of the capture window, like production.
                        if (armed) laidOut = true
                    }
                    Event.DRAW -> {
                        if (armed) drawGeometries.add(geometry)
                        // Mirrors onDrawCallback: record, sample, compare, report.
                        drawState.recordDraw()
                        val session = drawState.beginDrawSample()
                        if (session != null) {
                            val misaligned = rectsFor(geometry) != session.compareBaseline
                            drawState.recordMaskWalk(session.token, misaligned)
                        }
                    }
                }
            }
        }

        playGap(0, armed = false)
        drawState.reset()
        val token = drawState.beginMaskCapture()
        playGap(1, armed = true)
        val preWalkGeometry = geometry
        playGap(2, armed = true)
        // One arm attempt: production retries TORN_BY_DRAW with a fresh capture, which is
        // just another schedule of this model, so per-attempt checking covers the loop.
        val baselineOk = drawState.setBaseline(token, rectsFor(preWalkGeometry)) == BaselineResult.ARMED
        val drewDuringPreWalk =
            (schedule.eventsPerGap[1] + schedule.eventsPerGap[2]).contains(Event.DRAW)
        var kept = false
        if (baselineOk) {
            playGap(3, armed = true)
            val postWalkGeometry = geometry
            playGap(4, armed = true)
            kept =
                !drawState.isCaptureInvalid(token) &&
                drawState.finishMaskCapture(token, rectsFor(postWalkGeometry), poisoned = false) &&
                rectsFor(preWalkGeometry) == rectsFor(postWalkGeometry) &&
                !drawState.didLayoutSinceReset
        }
        drawState.cancelMaskCapture(token)
        return Outcome(kept, drawGeometries, preWalkGeometry, laidOut, drewDuringPreWalk)
    }

    private fun allSchedules(maxEvents: Int): Sequence<Schedule> =
        sequence {
            val alphabet = Event.entries

            suspend fun SequenceScope<Schedule>.extend(
                events: List<Event>,
                gaps: List<Int>,
            ) {
                // Distribute this event sequence over the 5 gaps (order-preserving).
                if (events.size == gaps.size) {
                    val perGap = List(5) { gap -> events.indices.filter { gaps[it] == gap }.map { events[it] } }
                    yield(Schedule(perGap))
                    return
                }
                val minGap = gaps.lastOrNull() ?: 0
                for (gap in minGap until 5) {
                    extend(events, gaps + gap)
                }
            }

            suspend fun SequenceScope<Schedule>.build(events: List<Event>) {
                extend(events, emptyList())
                if (events.size < maxEvents) {
                    for (event in alphabet) {
                        build(events + event)
                    }
                }
            }
            build(emptyList())
        }

    @Test
    fun `every schedule up to three events satisfies the safety and liveness contracts`() {
        var total = 0
        var kept = 0
        for (schedule in allSchedules(maxEvents = 3)) {
            total++
            val outcome = run(schedule)
            if (outcome.kept) kept++

            // Safety (fail closed): a kept frame requires that no layout ran, no draw
            // overlapped the pre-walk, and every draw committed while armed rendered exactly
            // the baseline geometry. (The pre-arming frame's geometry is a protocol-level
            // residual shared with main, not constrained here.)
            if (outcome.kept) {
                assertTrue(!outcome.laidOut, "kept despite a layout pass: $schedule")
                assertTrue(!outcome.drewDuringPreWalk, "kept despite a draw during the pre-walk: $schedule")
                for (drawGeometry in outcome.drawGeometries) {
                    assertEquals(
                        outcome.baselineGeometry,
                        drawGeometry,
                        "kept a draw whose geometry differs from the baseline: $schedule",
                    )
                }
            }

            // Liveness (the bug this PR fixes): stable-geometry schedules with no layout and
            // no pre-walk-overlapping draw MUST be kept, however many draws happen during the
            // PixelCopy window. Pixel-only animations may not blank the replay.
            val allEvents = schedule.eventsPerGap.drop(1).flatten()
            val shouldKeep =
                !allEvents.contains(Event.LAYOUT) &&
                    !allEvents.contains(Event.MUTATE) &&
                    !outcome.drewDuringPreWalk
            if (shouldKeep) {
                assertTrue(outcome.kept, "discarded a provably-stable frame: $schedule")
            }
        }

        // Exhaustiveness guard: sum over len 0..3 of 3^len * C(len+4,4) orderings.
        assertEquals(1 + 15 + 135 + 945, total, "schedule enumeration changed")
        assertTrue(kept > 0, "no schedule was kept; liveness assertions were vacuous")
    }

    // Pins the drawSamplesInProgress == 0 conjunct in finishMaskCapture: a draw whose mask
    // walk is still running when the post-copy walk finishes must fail closed, since that
    // walk's verdict has not landed yet. The enumeration above cannot reach this (its draws
    // are atomic), so it is checked directly.
    @Test
    fun `a draw sample still open at finish discards the capture`() {
        val drawState = WindowDrawState()
        val token = drawState.beginMaskCapture()
        val baseline = rectsFor(0)
        assertEquals(BaselineResult.ARMED, drawState.setBaseline(token, baseline))

        // A frame draws and starts sampling, but has not reported its walk yet.
        drawState.recordDraw()
        val session = drawState.beginDrawSample()
        assertTrue(session != null)

        assertTrue(
            !drawState.finishMaskCapture(token, baseline, poisoned = false),
            "finish must discard while a draw sample is in flight",
        )
    }
}

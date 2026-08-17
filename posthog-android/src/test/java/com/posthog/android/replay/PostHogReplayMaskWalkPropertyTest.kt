package com.posthog.android.replay

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.android.replay.internal.IntHashSet
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Property tests for the two data-structure tricks the mask-walk fast path relies on.
 * Seeded, so failures reproduce deterministically.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [26])
internal class PostHogReplayMaskWalkPropertyTest {
    private fun randomRect(random: Random): Rect {
        val left = random.nextInt(-50, 1000)
        val top = random.nextInt(-50, 2000)
        return Rect(left, top, left + random.nextInt(1, 400), top + random.nextInt(1, 400))
    }

    private fun randomRects(
        random: Random,
        size: Int = random.nextInt(0, 9),
    ): List<Rect> = List(size) { randomRect(random) }

    // The optimization under test: compare mode streams rects against the baseline instead of
    // storing a list and comparing at the end. Its verdict must be EXACTLY stored-list
    // (in)equality, or the keep/discard semantics silently change.
    @Test
    fun `compare-mode verdict equals stored-list equality across randomized mutations`() {
        val random = Random(676_1)
        repeat(10_000) { case ->
            val baseline = randomRects(random)
            val candidate = baseline.map(::Rect).toMutableList()
            // Mutation 0 keeps the candidate identical; the rest each break equality differently.
            when (random.nextInt(6)) {
                0 -> {}
                1 -> if (candidate.isNotEmpty()) candidate[random.nextInt(candidate.size)] = randomRect(random)
                2 -> candidate.add(random.nextInt(candidate.size + 1), randomRect(random))
                3 -> if (candidate.isNotEmpty()) candidate.removeAt(random.nextInt(candidate.size))
                4 ->
                    if (candidate.size >= 2) {
                        val i = random.nextInt(candidate.size - 1)
                        val tmp = candidate[i]
                        candidate[i] = candidate[i + 1]
                        candidate[i + 1] = tmp
                    }
                5 -> if (candidate.isNotEmpty()) candidate.subList(random.nextInt(candidate.size), candidate.size).clear()
            }

            val walk = PostHogReplayIntegration.MaskWalk()
            walk.resetForCompareAgainst(baseline)
            // Feed through one shared scratch instance, like the real walk does.
            val scratch = Rect()
            for (rect in candidate) {
                scratch.set(rect)
                walk.addRect(scratch)
            }

            assertEquals(
                candidate != baseline,
                walk.isMisaligned(),
                "case $case: baseline=$baseline candidate=$candidate",
            )
        }
    }

    @Test
    fun `compare mode stores nothing`() {
        val random = Random(676_2)
        val walk = PostHogReplayIntegration.MaskWalk()
        walk.resetForCompareAgainst(randomRects(random, 5))
        repeat(5) { walk.addRect(randomRect(random)) }

        assertTrue(walk.rects.isEmpty(), "compare mode must not allocate stored rects")
    }

    // Store mode must deep-copy: every walk feeds the same scratch Rect, so storing the
    // instance would alias all rects to the last value and break the pre/post comparison.
    @Test
    fun `store mode deep-copies the scratch rect`() {
        val walk = PostHogReplayIntegration.MaskWalk()
        val scratch = Rect(1, 2, 3, 4)
        walk.addRect(scratch)
        scratch.set(5, 6, 7, 8)
        walk.addRect(scratch)

        assertEquals(listOf(Rect(1, 2, 3, 4), Rect(5, 6, 7, 8)), walk.rects)
    }

    @Test
    fun `IntHashSet add matches HashSet oracle including zero and growth`() {
        val random = Random(676_3)
        val sut = IntHashSet()
        val oracle = HashSet<Int>()
        // Biased to collide (small range) plus the edge values the open addressing must handle.
        val edgeValues = intArrayOf(0, Int.MIN_VALUE, Int.MAX_VALUE, -1)
        repeat(50_000) { case ->
            val value =
                when (random.nextInt(4)) {
                    0 -> edgeValues[random.nextInt(edgeValues.size)]
                    else -> random.nextInt(-300, 300)
                }
            assertEquals(oracle.add(value), sut.add(value), "case $case: value=$value")
        }
        // Force growth well past the initial capacity with distinct values.
        val grown = IntHashSet()
        val grownOracle = HashSet<Int>()
        repeat(5_000) {
            val value = random.nextInt()
            assertEquals(grownOracle.add(value), grown.add(value), "growth: value=$value")
        }
        // clear() must forget everything, including the zero sentinel.
        grown.clear()
        assertTrue(grown.add(0))
        assertTrue(grown.add(42))
    }
}

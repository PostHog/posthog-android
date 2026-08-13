package com.posthog.android.replay

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.android.replay.internal.IntHashSet
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.Test

/**
 * Isolated microbenchmark of the two data-structure changes on the mask-walk hot path,
 * WITHOUT the view-tree walk. The full-tree benchmark (PostHogReplayMaskWalkBenchmark) is
 * dominated by Robolectric's getGlobalVisibleRect shadow, which both the old and new code call
 * identically, so it cannot isolate these changes. This one can: it measures only the rect
 * comparison and the visited-set, so the ns/op and (crucially) the exact bytes/op reflect the
 * SDK's own cost, and translate directly to on-device ART GC pressure.
 *
 *   POSTHOG_BENCHMARK=1 ./gradlew :posthog-android:testReleaseUnitTest \
 *     --tests '*PostHogReplayMaskWalkMicroBenchmark*' -i | grep BENCH
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [26])
internal class PostHogReplayMaskWalkMicroBenchmark {
    @Test
    fun `rect comparison old list-build vs new streaming`() {
        assumeTrue(System.getenv("POSTHOG_BENCHMARK") == "1")
        for (n in intArrayOf(8, 40)) {
            val baseline = List(n) { Rect(it, 0, it + 1, 1) }
            val liveEqual = baseline.map(::Rect)
            val liveEarlyMismatch = baseline.map(::Rect).toMutableList().also { it[0] = Rect(9, 9, 9, 9) }

            // The walk and its scratch are reused across frames in production (drawSampleWalk),
            // so per frame they allocate nothing; only the OLD list-build allocates.
            val walk = PostHogReplayIntegration.MaskWalk()
            val scratch = Rect()

            // OLD: build a fresh list per frame, then List.equals against the baseline.
            measure("old-listcompare-equal[n=$n]") {
                val built = ArrayList<Rect>(n)
                for (r in liveEqual) built.add(Rect(r))
                built == baseline
            }
            // NEW: stream each rect against the baseline, nothing stored.
            measure("new-streamcompare-equal[n=$n]") {
                walk.resetForCompareAgainst(baseline)
                for (r in liveEqual) {
                    scratch.set(r)
                    walk.addRect(scratch)
                }
                walk.isMisaligned()
            }
            // NEW early-exit: a first-rect mismatch stops the whole comparison immediately.
            measure("new-streamcompare-earlymismatch[n=$n]") {
                walk.resetForCompareAgainst(baseline)
                for (r in liveEarlyMismatch) {
                    if (walk.shouldStop) break
                    scratch.set(r)
                    walk.addRect(scratch)
                }
                walk.isMisaligned()
            }
        }
    }

    @Test
    fun `visited set old boxed HashSet vs new primitive IntHashSet`() {
        assumeTrue(System.getenv("POSTHOG_BENCHMARK") == "1")
        for (n in intArrayOf(60, 550)) {
            // Identity-hash-like keys: large, non-cached, so the old set boxes every one.
            val keys = IntArray(n) { (it * -0x61c88647) xor 0x9e3779b9.toInt() }

            measure("old-hashset-int[n=$n]") {
                val set = HashSet<Int>()
                var novel = 0
                for (k in keys) if (set.add(k)) novel++
                novel
            }
            val reused = IntHashSet()
            measure("new-inthashset[n=$n]") {
                reused.clear()
                var novel = 0
                for (k in keys) if (reused.add(k)) novel++
                novel
            }
        }
    }

    private fun measure(
        name: String,
        op: () -> Any?,
    ) {
        var sink = 0
        repeat(50_000) { sink = sink xor op().hashCode() }

        val allocatedBefore = threadAllocatedBytes()
        val iterations = 500_000
        val batches = 5
        val batchNs = LongArray(batches)
        for (b in 0 until batches) {
            val start = System.nanoTime()
            repeat(iterations / batches) { sink = sink xor op().hashCode() }
            batchNs[b] = (System.nanoTime() - start) / (iterations / batches)
        }
        val bytesPerOp = (threadAllocatedBytes() - allocatedBefore) / iterations
        batchNs.sort()

        println("BENCH $name ns/op=${batchNs[batches / 2]} bytes/op=$bytesPerOp sink=${sink and 1}")
    }

    @Suppress("DEPRECATION")
    private fun threadAllocatedBytes(): Long {
        return try {
            val factory = Class.forName("java.lang.management.ManagementFactory")
            val bean = factory.getMethod("getThreadMXBean").invoke(null)
            val method =
                Class.forName("com.sun.management.ThreadMXBean")
                    .getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
            method.invoke(bean, Thread.currentThread().id) as Long
        } catch (e: Throwable) {
            0L
        }
    }
}

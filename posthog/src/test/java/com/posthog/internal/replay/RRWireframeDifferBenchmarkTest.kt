package com.posthog.internal.replay

import kotlin.test.Test

/**
 * Benchmark test for RRWireframeDiffer hot-path operations.
 * Outputs METRIC lines for autoresearch consumption.
 */
internal class RRWireframeDifferBenchmarkTest {
    private companion object {
        private const val TREE_DEPTH = 4
        private const val CHILDREN_PER_NODE = 5 // 5^4 = 625 leaf nodes, ~780 total nodes
        private const val WARMUP_ITERATIONS = 50
        private const val BENCH_ITERATIONS = 200
    }

    /**
     * Builds a synthetic wireframe tree with the given depth and branching factor.
     */
    private fun buildTree(
        depth: Int,
        childrenPerNode: Int,
        idStart: Int = 1,
        parentId: Int? = null,
    ): Pair<RRWireframe, Int> {
        var nextId = idStart
        val myId = nextId++

        val children = mutableListOf<RRWireframe>()
        if (depth > 0) {
            for (i in 0 until childrenPerNode) {
                val (child, newNextId) = buildTree(depth - 1, childrenPerNode, nextId, myId)
                children.add(child)
                nextId = newNextId
            }
        }

        val wireframe =
            RRWireframe(
                id = myId,
                x = myId * 10,
                y = myId * 5,
                width = 100,
                height = 50,
                type = if (depth == 0) "text" else null,
                text = if (depth == 0) "Hello World $myId" else null,
                style =
                    RRStyle(
                        backgroundColor = "#FF0000",
                        color = if (depth == 0) "#000000" else null,
                        fontSize = if (depth == 0) 14 else null,
                    ),
                childWireframes = children.ifEmpty { null },
                parentId = parentId,
            )

        return Pair(wireframe, nextId)
    }

    /**
     * Builds a slightly modified version of a tree (simulates incremental changes).
     * Changes ~10% of leaf nodes' text and positions.
     */
    private fun buildModifiedTree(
        depth: Int,
        childrenPerNode: Int,
        idStart: Int = 1,
        parentId: Int? = null,
        changeEvery: Int = 10,
    ): Pair<RRWireframe, Int> {
        var nextId = idStart
        val myId = nextId++

        val children = mutableListOf<RRWireframe>()
        if (depth > 0) {
            for (i in 0 until childrenPerNode) {
                val (child, newNextId) = buildModifiedTree(depth - 1, childrenPerNode, nextId, myId, changeEvery)
                children.add(child)
                nextId = newNextId
            }
        }

        val isModified = myId % changeEvery == 0

        val wireframe =
            RRWireframe(
                id = myId,
                x = if (isModified) myId * 10 + 5 else myId * 10,
                y = myId * 5,
                width = 100,
                height = 50,
                type = if (depth == 0) "text" else null,
                text =
                    if (depth == 0 && isModified) {
                        "Modified $myId"
                    } else if (depth == 0) {
                        "Hello World $myId"
                    } else {
                        null
                    },
                style =
                    RRStyle(
                        backgroundColor = "#FF0000",
                        color = if (depth == 0) "#000000" else null,
                        fontSize = if (depth == 0) 14 else null,
                    ),
                childWireframes = children.ifEmpty { null },
                parentId = parentId,
            )

        return Pair(wireframe, nextId)
    }

    @Test
    fun benchmarkFullDiffCycle() {
        val (oldTree, _) = buildTree(TREE_DEPTH, CHILDREN_PER_NODE)
        val (newTree, _) = buildModifiedTree(TREE_DEPTH, CHILDREN_PER_NODE)

        // Warmup
        repeat(WARMUP_ITERATIONS) {
            RRWireframeDiffer.diffTrees(listOf(oldTree), listOf(newTree))
        }

        // Benchmark
        val times = LongArray(BENCH_ITERATIONS)
        for (i in 0 until BENCH_ITERATIONS) {
            val start = System.nanoTime()
            RRWireframeDiffer.diffTrees(listOf(oldTree), listOf(newTree))
            times[i] = System.nanoTime() - start
        }

        times.sort()
        val median = times[BENCH_ITERATIONS / 2]
        val p95 = times[(BENCH_ITERATIONS * 0.95).toInt()]
        val medianUs = median / 1000

        // Count total nodes for context
        val flatCount = RRWireframeDiffer.flattenChildren(listOf(oldTree)).size

        println("METRIC total_µs=$medianUs")
        println("METRIC p95_µs=${p95 / 1000}")
        println("METRIC node_count=$flatCount")
    }

    @Test
    fun benchmarkToRGBColor() {
        val colors = IntArray(1000) { it * 256 + 0xFF }

        // Warmup
        repeat(WARMUP_ITERATIONS) {
            for (c in colors) {
                RRWireframeDiffer.toRGBColor(c)
            }
        }

        // Benchmark
        val times = LongArray(BENCH_ITERATIONS)
        for (i in 0 until BENCH_ITERATIONS) {
            val start = System.nanoTime()
            for (c in colors) {
                RRWireframeDiffer.toRGBColor(c)
            }
            times[i] = System.nanoTime() - start
        }

        times.sort()
        val median = times[BENCH_ITERATIONS / 2]
        val medianUs = median / 1000

        println("METRIC color_µs=$medianUs")
    }

    @Test
    fun benchmarkMask() {
        val texts = List(500) { "Hello World this is text number $it" }

        // Warmup
        repeat(WARMUP_ITERATIONS) {
            for (t in texts) {
                RRWireframeDiffer.mask(t)
            }
        }

        // Benchmark
        val times = LongArray(BENCH_ITERATIONS)
        for (i in 0 until BENCH_ITERATIONS) {
            val start = System.nanoTime()
            for (t in texts) {
                RRWireframeDiffer.mask(t)
            }
            times[i] = System.nanoTime() - start
        }

        times.sort()
        val median = times[BENCH_ITERATIONS / 2]
        val medianUs = median / 1000

        println("METRIC mask_µs=$medianUs")
    }
}

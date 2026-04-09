package com.posthog.internal.replay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Correctness tests for RRWireframeDiffer.
 */
internal class RRWireframeDifferTest {
    @Test
    fun diffTreesIdenticalTreesProducesNoChanges() {
        val tree = buildSimpleTree()
        val (added, removed, updated) = RRWireframeDiffer.diffTrees(listOf(tree), listOf(tree))
        assertTrue(added.isEmpty(), "Expected no added items")
        assertTrue(removed.isEmpty(), "Expected no removed items")
        // updated may contain the root if references differ, but with same object it should be empty
        assertTrue(updated.isEmpty(), "Expected no updated items")
    }

    @Test
    fun diffTreesDetectsUpdatedNode() {
        val old = RRWireframe(id = 1, x = 0, y = 0, width = 100, height = 50, text = "Hello")
        val new = RRWireframe(id = 1, x = 10, y = 0, width = 100, height = 50, text = "Hello")
        val (added, removed, updated) = RRWireframeDiffer.diffTrees(listOf(old), listOf(new))
        assertTrue(added.isEmpty())
        assertTrue(removed.isEmpty())
        assertEquals(1, updated.size)
        assertEquals(10, updated[0].x)
    }

    @Test
    fun diffTreesDetectsAddedNode() {
        val old = RRWireframe(id = 1, x = 0, y = 0, width = 100, height = 50)
        val child = RRWireframe(id = 2, x = 10, y = 10, width = 50, height = 25, parentId = 1)
        val new = RRWireframe(id = 1, x = 0, y = 0, width = 100, height = 50, childWireframes = listOf(child))
        val (added, removed, _) = RRWireframeDiffer.diffTrees(listOf(old), listOf(new))
        assertEquals(1, added.size)
        assertEquals(2, added[0].id)
        assertTrue(removed.isEmpty())
    }

    @Test
    fun diffTreesDetectsRemovedNode() {
        val child = RRWireframe(id = 2, x = 10, y = 10, width = 50, height = 25, parentId = 1)
        val old = RRWireframe(id = 1, x = 0, y = 0, width = 100, height = 50, childWireframes = listOf(child))
        val new = RRWireframe(id = 1, x = 0, y = 0, width = 100, height = 50)
        val (added, removed, _) = RRWireframeDiffer.diffTrees(listOf(old), listOf(new))
        assertTrue(added.isEmpty())
        assertEquals(1, removed.size)
        assertEquals(2, removed[0].id)
    }

    @Test
    fun diffTreesHandlesStructuralChange() {
        // Old: root -> [A, B]
        // New: root -> [C, B]  (A replaced by C)
        val a = RRWireframe(id = 10, x = 0, y = 0, width = 50, height = 25, parentId = 1)
        val b = RRWireframe(id = 20, x = 50, y = 0, width = 50, height = 25, parentId = 1)
        val c = RRWireframe(id = 30, x = 0, y = 0, width = 50, height = 25, parentId = 1)
        val old = RRWireframe(id = 1, x = 0, y = 0, width = 100, height = 50, childWireframes = listOf(a, b))
        val new = RRWireframe(id = 1, x = 0, y = 0, width = 100, height = 50, childWireframes = listOf(c, b))
        val (added, removed, _) = RRWireframeDiffer.diffTrees(listOf(old), listOf(new))
        // A (id=10) at position 0 and C (id=30) at position 0 have different IDs
        // They become orphans: A goes to old orphans, C to new orphans
        // HashMap reconciliation: C (id=30) not in old -> added; A (id=10) not in new -> removed
        assertEquals(1, added.size)
        assertEquals(30, added[0].id)
        assertEquals(1, removed.size)
        assertEquals(10, removed[0].id)
    }

    @Test
    fun diffTreesNullOldRoot() {
        val new = RRWireframe(
            id = 1,
            x = 0,
            y = 0,
            width = 100,
            height = 50,
            childWireframes = listOf(
                RRWireframe(id = 2, x = 10, y = 10, width = 50, height = 25),
            ),
        )
        val (added, removed, updated) = RRWireframeDiffer.diffTrees(null, new)
        assertEquals(2, added.size) // root + child
        assertTrue(removed.isEmpty())
        assertTrue(updated.isEmpty())
    }

    @Test
    fun toRGBColorProducesCorrectValues() {
        assertEquals("#000000", RRWireframeDiffer.toRGBColor(0x000000))
        assertEquals("#FFFFFF", RRWireframeDiffer.toRGBColor(0xFFFFFF))
        assertEquals("#FF0000", RRWireframeDiffer.toRGBColor(0xFF0000))
        assertEquals("#00FF00", RRWireframeDiffer.toRGBColor(0x00FF00))
        assertEquals("#0000FF", RRWireframeDiffer.toRGBColor(0x0000FF))
        // Alpha should be stripped
        assertEquals("#ABCDEF", RRWireframeDiffer.toRGBColor(0xFFABCDEF.toInt()))
    }

    @Test
    fun flattenChildrenProducesCorrectOrder() {
        val child1 = RRWireframe(id = 2, x = 0, y = 0, width = 50, height = 25)
        val child2 = RRWireframe(id = 3, x = 50, y = 0, width = 50, height = 25)
        val root = RRWireframe(
            id = 1,
            x = 0,
            y = 0,
            width = 100,
            height = 50,
            childWireframes = listOf(child1, child2),
        )
        val flat = RRWireframeDiffer.flattenChildren(listOf(root))
        assertEquals(3, flat.size)
        assertEquals(1, flat[0].id)
        assertEquals(2, flat[1].id)
        assertEquals(3, flat[2].id)
    }

    private fun buildSimpleTree(): RRWireframe {
        return RRWireframe(
            id = 1,
            x = 0,
            y = 0,
            width = 100,
            height = 50,
            style = RRStyle(backgroundColor = "#FF0000"),
            childWireframes = listOf(
                RRWireframe(id = 2, x = 10, y = 10, width = 50, height = 25, text = "Hello", parentId = 1),
                RRWireframe(id = 3, x = 60, y = 10, width = 30, height = 25, type = "image", parentId = 1),
            ),
        )
    }
}

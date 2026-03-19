package com.posthog.internal.replay

import com.posthog.PostHogInternal

/**
 * Pure algorithmic utilities for diffing RRWireframe trees.
 * Extracted from PostHogReplayIntegration for testability and benchmarking.
 */
@PostHogInternal
public object RRWireframeDiffer {
    /**
     * Flattens a list of wireframes and all their nested children into a single flat list.
     * Uses a recursive helper that appends directly to a single result list,
     * avoiding intermediate list creation and stack overhead.
     */
    public fun flattenChildren(wireframes: List<RRWireframe>): List<RRWireframe> {
        val result = ArrayList<RRWireframe>(wireframes.size * 4)
        flattenInto(wireframes, result)
        return result
    }

    private fun flattenInto(
        wireframes: List<RRWireframe>,
        result: ArrayList<RRWireframe>,
    ) {
        for (item in wireframes) {
            result.add(item)
            val children = item.childWireframes
            if (children != null) {
                flattenInto(children, result)
            }
        }
    }

    /**
     * Finds added, removed, and updated wireframes between old and new flat lists.
     * Returns Triple(added, removed, updated).
     * Single-pass approach: build a map of old items, then iterate new items once.
     */
    public fun findAddedAndRemovedItems(
        oldItems: List<RRWireframe>,
        newItems: List<RRWireframe>,
    ): Triple<List<RRWireframe>, List<RRWireframe>, List<RRWireframe>> {
        // Build mutable map of old items — entries are removed as they're matched,
        // so whatever remains at the end is the removed set. Avoids a separate HashSet.
        val oldMap = HashMap<Int, RRWireframe>(oldItems.size * 2)
        for (item in oldItems) {
            oldMap[item.id] = item
        }

        val addedItems = ArrayList<RRWireframe>()
        val updatedItems = ArrayList<RRWireframe>()

        for (newItem in newItems) {
            val oldItem = oldMap.remove(newItem.id)
            if (oldItem == null) {
                addedItems.add(newItem)
            } else {
                if (!wireframePropertiesEqual(oldItem, newItem)) {
                    updatedItems.add(newItem)
                }
            }
        }

        // Remaining entries in oldMap are items that were removed
        val removedItems = ArrayList<RRWireframe>(oldMap.size)
        removedItems.addAll(oldMap.values)

        return Triple(addedItems, removedItems, updatedItems)
    }

    /**
     * Combined flatten + diff using parallel tree walk with HashMap fallback.
     *
     * Since consecutive frames usually have identical tree structure, we walk both
     * trees in parallel. When nodes at the same position share the same ID (common case),
     * we compare directly without any HashMap lookup. When IDs diverge or list sizes
     * differ, those subtrees are collected and reconciled via a HashMap at the end.
     */
    /**
     * Convenience overload for single-root trees (the common case in session replay).
     * Avoids allocating wrapper lists by handling the single-root case directly.
     */
    public fun diffTrees(
        oldRoot: RRWireframe?,
        newRoot: RRWireframe,
    ): Triple<List<RRWireframe>, List<RRWireframe>, List<RRWireframe>> {
        if (oldRoot == null) {
            // No previous snapshot — everything is added
            val added = ArrayList<RRWireframe>()
            flattenNodeInto(newRoot, added)
            return Triple(added, emptyList(), emptyList())
        }
        return diffTrees(listOf(oldRoot), listOf(newRoot))
    }

    public fun diffTrees(
        oldTree: List<RRWireframe>,
        newTree: List<RRWireframe>,
    ): Triple<List<RRWireframe>, List<RRWireframe>, List<RRWireframe>> {
        val addedItems = ArrayList<RRWireframe>()
        val removedItems = ArrayList<RRWireframe>()
        val updatedItems = ArrayList<RRWireframe>()

        // Collect orphans for HashMap reconciliation only when structure diverges
        val oldOrphans = ArrayList<RRWireframe>()
        val newOrphans = ArrayList<RRWireframe>()

        parallelWalk(oldTree, newTree, addedItems, removedItems, updatedItems, oldOrphans, newOrphans)

        // Reconcile structurally mismatched nodes via HashMap (rare path)
        if (oldOrphans.isNotEmpty() && newOrphans.isNotEmpty()) {
            val oldMap = HashMap<Int, RRWireframe>(oldOrphans.size * 2)
            for (item in oldOrphans) {
                oldMap[item.id] = item
            }
            for (newItem in newOrphans) {
                val oldItem = oldMap.remove(newItem.id)
                if (oldItem == null) {
                    addedItems.add(newItem)
                } else if (!wireframePropertiesEqual(oldItem, newItem)) {
                    updatedItems.add(newItem)
                }
            }
            removedItems.addAll(oldMap.values)
        } else if (oldOrphans.isNotEmpty()) {
            removedItems.addAll(oldOrphans)
        } else if (newOrphans.isNotEmpty()) {
            addedItems.addAll(newOrphans)
        }

        return Triple(addedItems, removedItems, updatedItems)
    }

    private fun parallelWalk(
        oldList: List<RRWireframe>,
        newList: List<RRWireframe>,
        added: ArrayList<RRWireframe>,
        removed: ArrayList<RRWireframe>,
        updated: ArrayList<RRWireframe>,
        oldOrphans: ArrayList<RRWireframe>,
        newOrphans: ArrayList<RRWireframe>,
    ) {
        val oldSize = oldList.size
        val newSize = newList.size

        // Fast path: same-size lists (very common for stable UIs)
        if (oldSize == newSize) {
            for (i in 0 until oldSize) {
                val oldItem = oldList[i]
                val newItem = newList[i]
                if (oldItem.id == newItem.id) {
                    if (!wireframePropertiesEqual(oldItem, newItem)) {
                        updated.add(newItem)
                    }
                    val oldChildren = oldItem.childWireframes
                    val newChildren = newItem.childWireframes
                    if (oldChildren != null && newChildren != null) {
                        parallelWalk(oldChildren, newChildren, added, removed, updated, oldOrphans, newOrphans)
                    } else if (oldChildren != null) {
                        flattenInto(oldChildren, removed)
                    } else if (newChildren != null) {
                        flattenInto(newChildren, added)
                    }
                } else {
                    flattenNodeInto(oldItem, oldOrphans)
                    flattenNodeInto(newItem, newOrphans)
                }
            }
        } else {
            val minSize = minOf(oldSize, newSize)
            for (i in 0 until minSize) {
                val oldItem = oldList[i]
                val newItem = newList[i]
                if (oldItem.id == newItem.id) {
                    if (!wireframePropertiesEqual(oldItem, newItem)) {
                        updated.add(newItem)
                    }
                    val oldChildren = oldItem.childWireframes
                    val newChildren = newItem.childWireframes
                    if (oldChildren != null && newChildren != null) {
                        parallelWalk(oldChildren, newChildren, added, removed, updated, oldOrphans, newOrphans)
                    } else if (oldChildren != null) {
                        flattenInto(oldChildren, removed)
                    } else if (newChildren != null) {
                        flattenInto(newChildren, added)
                    }
                } else {
                    flattenNodeInto(oldItem, oldOrphans)
                    flattenNodeInto(newItem, newOrphans)
                }
            }
            for (i in minSize until oldSize) {
                flattenNodeInto(oldList[i], oldOrphans)
            }
            for (i in minSize until newSize) {
                flattenNodeInto(newList[i], newOrphans)
            }
        }
    }

    /** Flatten a single node and all its descendants into a list. */
    private fun flattenNodeInto(
        wireframe: RRWireframe,
        result: ArrayList<RRWireframe>,
    ) {
        result.add(wireframe)
        val children = wireframe.childWireframes
        if (children != null) {
            flattenInto(children, result)
        }
    }

    /**
     * Compares two wireframes by all properties except childWireframes,
     * avoiding the allocation of copy() calls.
     */
    private fun wireframePropertiesEqual(
        a: RRWireframe,
        b: RRWireframe,
    ): Boolean {
        // Check cheap primitive fields first, then strings, then expensive object comparisons last.
        // Short-circuit on the most likely to differ (position, text content).
        return a.id == b.id &&
            a.x == b.x &&
            a.y == b.y &&
            a.width == b.width &&
            a.height == b.height &&
            a.disabled == b.disabled &&
            a.checked == b.checked &&
            a.max == b.max &&
            a.text == b.text &&
            a.type == b.type &&
            a.inputType == b.inputType &&
            a.label == b.label &&
            a.parentId == b.parentId &&
            a.value == b.value &&
            a.base64 == b.base64 &&
            // style is the most expensive comparison (data class with 17 fields)
            // Check reference equality first as an optimization
            (a.style === b.style || a.style == b.style) &&
            a.options == b.options
    }

    private val HEX_CHARS = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F')

    /**
     * Converts an RGB int to a hex color string like "#RRGGBB".
     * Uses manual char array conversion instead of String.format for ~10-50x speedup.
     */
    public fun toRGBColor(color: Int): String {
        val rgb = color and 0xFFFFFF
        val chars = CharArray(7)
        chars[0] = '#'
        chars[1] = HEX_CHARS[(rgb shr 20) and 0xF]
        chars[2] = HEX_CHARS[(rgb shr 16) and 0xF]
        chars[3] = HEX_CHARS[(rgb shr 12) and 0xF]
        chars[4] = HEX_CHARS[(rgb shr 8) and 0xF]
        chars[5] = HEX_CHARS[(rgb shr 4) and 0xF]
        chars[6] = HEX_CHARS[rgb and 0xF]
        return String(chars)
    }

    /**
     * Masks a string by replacing all characters with '*'.
     */
    public fun mask(text: String): String {
        return "*".repeat(text.length)
    }
}

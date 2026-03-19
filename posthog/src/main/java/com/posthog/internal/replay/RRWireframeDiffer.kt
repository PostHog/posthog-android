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
     * Combined flatten + diff in one pass: builds the old map while traversing the old tree,
     * then diffs while traversing the new tree. Avoids allocating two flat lists entirely.
     */
    public fun diffTrees(
        oldTree: List<RRWireframe>,
        newTree: List<RRWireframe>,
    ): Triple<List<RRWireframe>, List<RRWireframe>, List<RRWireframe>> {
        // Phase 1: Build map from old tree via traversal (no flat list needed)
        val oldMap = HashMap<Int, RRWireframe>(128)
        buildMapFromTree(oldTree, oldMap)

        // Phase 2: Traverse new tree, diff against old map
        val addedItems = ArrayList<RRWireframe>()
        val updatedItems = ArrayList<RRWireframe>()
        diffNewTree(newTree, oldMap, addedItems, updatedItems)

        // Remaining entries = removed
        val removedItems = ArrayList<RRWireframe>(oldMap.size)
        removedItems.addAll(oldMap.values)

        return Triple(addedItems, removedItems, updatedItems)
    }

    private fun buildMapFromTree(
        wireframes: List<RRWireframe>,
        map: HashMap<Int, RRWireframe>,
    ) {
        for (item in wireframes) {
            map[item.id] = item
            val children = item.childWireframes
            if (children != null) {
                buildMapFromTree(children, map)
            }
        }
    }

    private fun diffNewTree(
        wireframes: List<RRWireframe>,
        oldMap: HashMap<Int, RRWireframe>,
        addedItems: ArrayList<RRWireframe>,
        updatedItems: ArrayList<RRWireframe>,
    ) {
        for (newItem in wireframes) {
            val oldItem = oldMap.remove(newItem.id)
            if (oldItem == null) {
                addedItems.add(newItem)
            } else if (!wireframePropertiesEqual(oldItem, newItem)) {
                updatedItems.add(newItem)
            }
            val children = newItem.childWireframes
            if (children != null) {
                diffNewTree(children, oldMap, addedItems, updatedItems)
            }
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

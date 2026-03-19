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
     */
    public fun flattenChildren(wireframes: List<RRWireframe>): List<RRWireframe> {
        val result = mutableListOf<RRWireframe>()
        for (item in wireframes) {
            result.add(item)
            item.childWireframes?.let {
                result.addAll(flattenChildren(it))
            }
        }
        return result
    }

    /**
     * Finds added, removed, and updated wireframes between old and new flat lists.
     * Returns Triple(added, removed, updated).
     */
    public fun findAddedAndRemovedItems(
        oldItems: List<RRWireframe>,
        newItems: List<RRWireframe>,
    ): Triple<List<RRWireframe>, List<RRWireframe>, List<RRWireframe>> {
        val oldMap = oldItems.associateBy { it.id }
        val newMap = newItems.associateBy { it.id }

        val oldItemIds = HashSet(oldItems.map { it.id })
        val newItemIds = HashSet(newItems.map { it.id })

        val addedIds = newItemIds - oldItemIds
        val addedItems = newItems.filter { it.id in addedIds }

        val removedIds = oldItemIds - newItemIds
        val removedItems = oldItems.filter { it.id in removedIds }

        val updatedItems = mutableListOf<RRWireframe>()
        val sameItems = oldItemIds.intersect(newItemIds)

        for (id in sameItems) {
            val oldItem = oldMap[id]?.copy(childWireframes = null) ?: continue
            val newItem = newMap[id] ?: continue
            val newItemCopy = newItem.copy(childWireframes = null)

            if (oldItem != newItemCopy) {
                updatedItems.add(newItem)
            }
        }

        return Triple(addedItems, removedItems, updatedItems)
    }

    /**
     * Converts an RGB int to a hex color string like "#RRGGBB".
     */
    public fun toRGBColor(color: Int): String {
        return String.format("#%06X", (0xFFFFFF and color))
    }

    /**
     * Masks a string by replacing all characters with '*'.
     */
    public fun mask(text: String): String {
        return "*".repeat(text.length)
    }
}

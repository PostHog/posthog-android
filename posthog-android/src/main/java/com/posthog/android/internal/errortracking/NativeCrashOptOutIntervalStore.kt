package com.posthog.android.internal.errortracking

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences

/**
 * Remembers the wall-clock intervals during which the user was opted out, so
 * native crash records from those intervals are never captured once the user
 * opts back in.
 *
 * Bounds are wall-clock ms because exit records carry wall-clock timestamps.
 * The wall clock can move backwards while opted out, so bounds are ordered
 * rather than trusted to be.
 */
internal class NativeCrashOptOutIntervalStore(context: Context, projectToken: String) {
    private val preferences = context.nativeCrashPreferences()
    private val openKey = "$OPEN_KEY_PREFIX$projectToken"
    private val closedKey = "$CLOSED_KEY_PREFIX$projectToken"

    // open() and close() commit() rather than apply(): a native crash kills the process before apply()
    // flushes, which would lose the interval or leave it open over consented crashes.
    @SuppressLint("ApplySharedPref")
    fun open(nowMs: Long) {
        synchronized(lock) {
            val since = openSince()
            if (since != null && since <= nowMs) {
                return
            }
            if (since != null) {
                // clock moved back: keep the span to the old start covered
                close(nowMs)
            }
            commitOrThrow(preferences.edit().putLong(openKey, nowMs))
        }
    }

    @SuppressLint("ApplySharedPref")
    fun close(nowMs: Long) {
        synchronized(lock) {
            val since = openSince() ?: return
            val interval = minOf(since, nowMs)..maxOf(since, nowMs)
            val intervals = bounded(parseIntervals(preferences.getString(closedKey, null)) + listOf(interval))
            commitOrThrow(
                preferences.edit()
                    .putString(closedKey, serialize(intervals))
                    .remove(openKey),
            )
        }
    }

    // a false commit() is as lost as an exception, so surface it to the caller's failure log
    @SuppressLint("ApplySharedPref")
    private fun commitOrThrow(editor: SharedPreferences.Editor) {
        check(editor.commit()) { "the native crash preferences could not be written" }
    }

    /** Every opt-out interval of every project token, with open ones extended to [nowMs]. */
    fun intervals(nowMs: Long): List<LongRange> {
        synchronized(lock) {
            // a crash recorded while any client was opted out was recorded without full consent, so it is dropped
            val all = preferences.all
            val closed = keysWithPrefix(all, CLOSED_KEY_PREFIX).flatMap { parseIntervals(all[it] as? String) }
            // the clock can move back while still opted out
            val open =
                keysWithPrefix(all, OPEN_KEY_PREFIX)
                    .mapNotNull { all[it] as? Long }
                    .map { since -> minOf(since, nowMs)..maxOf(since, nowMs) }
            return closed + open
        }
    }

    // records at or below the watermark are never scanned again
    fun prune(watermarkMs: Long) {
        synchronized(lock) {
            val all = preferences.all
            val editor = preferences.edit()
            var changed = false
            keysWithPrefix(all, CLOSED_KEY_PREFIX).forEach { key ->
                val intervals = parseIntervals(all[key] as? String)
                val kept = intervals.filter { it.last > watermarkMs }
                if (kept.size != intervals.size) {
                    editor.putString(key, serialize(kept))
                    changed = true
                }
            }
            if (changed) {
                editor.apply()
            }
        }
    }

    private fun openSince(): Long? = if (preferences.contains(openKey)) preferences.getLong(openKey, 0L) else null

    private fun keysWithPrefix(
        all: Map<String, *>,
        prefix: String,
    ): List<String> = all.keys.filter { it.startsWith(prefix) }

    // insertion order, which is not wall-clock order after a backward clock
    private fun parseIntervals(serialized: String?): List<LongRange> =
        serialized
            ?.split(';')
            ?.mapNotNull { entry ->
                val parts = entry.split(':')
                val start = parts.getOrNull(0)?.toLongOrNull()
                val end = parts.getOrNull(1)?.toLongOrNull()
                if (start != null && end != null) start..end else null
            }
            ?: emptyList()

    private fun serialize(intervals: List<LongRange>): String = intervals.joinToString(";") { "${it.first}:${it.last}" }

    // dropping an interval could expose a crash the scan has not acknowledged yet, so merge the two oldest instead,
    // spanning both whatever their wall-clock order
    private fun bounded(intervals: List<LongRange>): List<LongRange> {
        if (intervals.size <= MAX_INTERVALS) return intervals
        val (a, b) = intervals
        val merged = minOf(a.first, b.first)..maxOf(a.last, b.last)
        return listOf(merged) + intervals.drop(2)
    }

    private companion object {
        private const val OPEN_KEY_PREFIX = "optOutSince:"
        private const val CLOSED_KEY_PREFIX = "optOutIntervals:"

        // every instance shares one SharedPreferences file, so the lock is shared too
        private val lock = Any()

        // bounds preference growth; the OS keeps few exit records
        private const val MAX_INTERVALS = 32
    }
}

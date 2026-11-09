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
internal class NativeCrashOptOutIntervalStore(context: Context) {
    private val preferences = context.nativeCrashPreferences()

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
            commitOrThrow(preferences.edit().putLong(OPEN_KEY, nowMs))
        }
    }

    @SuppressLint("ApplySharedPref")
    fun close(nowMs: Long) {
        synchronized(lock) {
            val since = openSince() ?: return
            val interval = minOf(since, nowMs) to maxOf(since, nowMs)
            val intervals = (closedIntervals() + interval).takeLast(MAX_INTERVALS)
            commitOrThrow(
                preferences.edit()
                    .putString(CLOSED_KEY, serialize(intervals))
                    .remove(OPEN_KEY),
            )
        }
    }

    // a false commit() is as lost as an exception, so surface it to the caller's failure log
    @SuppressLint("ApplySharedPref")
    private fun commitOrThrow(editor: SharedPreferences.Editor) {
        check(editor.commit()) { "the native crash preferences could not be written" }
    }

    /** Every opt-out interval, with an open one extended to [nowMs]. */
    fun intervals(nowMs: Long): List<LongRange> {
        synchronized(lock) {
            val closed = closedIntervals().map { (start, end) -> start..end }
            val open = openSince() ?: return closed
            // the clock can move back while still opted out
            return closed + listOf(minOf(open, nowMs)..maxOf(open, nowMs))
        }
    }

    // records at or below the watermark are never scanned again
    fun prune(watermarkMs: Long) {
        synchronized(lock) {
            val intervals = closedIntervals()
            val kept = intervals.filter { it.second > watermarkMs }
            if (kept.size != intervals.size) {
                preferences.edit().putString(CLOSED_KEY, serialize(kept)).apply()
            }
        }
    }

    private fun openSince(): Long? = if (preferences.contains(OPEN_KEY)) preferences.getLong(OPEN_KEY, 0L) else null

    // oldest first
    private fun closedIntervals(): List<Pair<Long, Long>> =
        preferences.getString(CLOSED_KEY, null)
            ?.split(';')
            ?.mapNotNull { entry ->
                val parts = entry.split(':')
                val start = parts.getOrNull(0)?.toLongOrNull()
                val end = parts.getOrNull(1)?.toLongOrNull()
                if (start != null && end != null) start to end else null
            }
            ?: emptyList()

    private fun serialize(intervals: List<Pair<Long, Long>>): String = intervals.joinToString(";") { "${it.first}:${it.second}" }

    private companion object {
        private const val OPEN_KEY = "optOutSince"
        private const val CLOSED_KEY = "optOutIntervals"

        // every instance shares one SharedPreferences file, so the lock is shared too
        private val lock = Any()

        // bounds preference growth; the OS keeps few exit records
        private const val MAX_INTERVALS = 32
    }
}

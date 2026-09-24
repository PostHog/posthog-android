package com.posthog.android.internal.errortracking

import android.content.Context

/**
 * Remembers, per app run, how far the SDK's clock was from the wall clock.
 *
 * Exit records only carry wall-clock time, and a native crash is recovered in a
 * later run whose clocks may disagree differently (the user changed the time, or
 * network time became available). Recovering with the crashed run's offset puts
 * the crash on the SDK's clock as it was when the crash happened.
 */
internal class NativeCrashClockOffsetStore(context: Context) {
    private val preferences =
        context.getSharedPreferences("posthog-native-crash", Context.MODE_PRIVATE)

    /** The offset of the latest run that started at or before [wallClockMs], or null if none is known. */
    fun offsetAt(wallClockMs: Long): Long? = runs().lastOrNull { (startMs, _) -> startMs <= wallClockMs }?.second

    fun record(
        runStartWallClockMs: Long,
        offsetMs: Long,
    ) {
        val runs = (runs() + (runStartWallClockMs to offsetMs)).sortedBy { it.first }.takeLast(MAX_RUNS)
        preferences.edit().putString(KEY, runs.joinToString(";") { "${it.first}:${it.second}" }).apply()
    }

    private fun runs(): List<Pair<Long, Long>> =
        preferences.getString(KEY, null)
            ?.split(';')
            ?.mapNotNull { entry ->
                val parts = entry.split(':')
                val start = parts.getOrNull(0)?.toLongOrNull()
                val offset = parts.getOrNull(1)?.toLongOrNull()
                if (start != null && offset != null) start to offset else null
            }
            ?.sortedBy { it.first }
            ?: emptyList()

    private companion object {
        private const val KEY = "clockOffsets"

        // Crashes older than this many runs fall back to the oldest known offset,
        // which is fine: the OS only keeps a handful of exit records anyway.
        private const val MAX_RUNS = 16
    }
}

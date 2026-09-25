package com.posthog.android.internal.errortracking

import android.content.Context

/**
 * Remembers, per app run, how far the SDK's clock was from the wall clock.
 *
 * Exit records only carry wall-clock time, and a native crash is recovered in a
 * later run whose clocks may disagree differently (the user changed the time, or
 * network time became available). Recovering with the crashed run's offset puts
 * the crash on the SDK's clock as it was when the crash happened.
 *
 * Runs are keyed by pid, which the exit record carries, rather than by start
 * time: the wall clock can move backwards, so start times do not order runs.
 */
internal class NativeCrashClockOffsetStore(context: Context) {
    private val preferences =
        context.getSharedPreferences("posthog-native-crash", Context.MODE_PRIVATE)

    /** The offset last recorded by the run with [pid], or null if none is known. */
    fun offsetFor(pid: Int): Long? = runs().lastOrNull { it.first == pid }?.second

    fun record(
        pid: Int,
        offsetMs: Long,
    ) {
        // Scans on different executors can overlap after an uninstall and
        // re-enable, so the read-modify-write must not lose either run's entry.
        synchronized(lock) {
            // a reused pid belongs to the newer run, so drop the older entry
            val runs = (runs().filter { it.first != pid } + (pid to offsetMs)).takeLast(MAX_RUNS)
            preferences.edit().putString(KEY, runs.joinToString(";") { "${it.first}:${it.second}" }).apply()
        }
    }

    // oldest first
    private fun runs(): List<Pair<Int, Long>> =
        preferences.getString(KEY, null)
            ?.split(';')
            ?.mapNotNull { entry ->
                val parts = entry.split(':')
                val pid = parts.getOrNull(0)?.toIntOrNull()
                val offset = parts.getOrNull(1)?.toLongOrNull()
                if (pid != null && offset != null) pid to offset else null
            }
            ?: emptyList()

    private companion object {
        private const val KEY = "clockOffsets"

        // every instance shares one SharedPreferences file, so the lock is shared too
        private val lock = Any()

        // Crashes from runs older than this fall back to the current offset,
        // which is fine: the OS only keeps a handful of exit records anyway.
        private const val MAX_RUNS = 16
    }
}

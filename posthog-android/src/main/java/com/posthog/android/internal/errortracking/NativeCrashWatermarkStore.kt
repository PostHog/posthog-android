package com.posthog.android.internal.errortracking

import android.annotation.SuppressLint
import android.content.Context

/**
 * Persists the timestamp of the newest native crash record already captured.
 *
 * Deliberately its own file rather than the shared
 * [com.posthog.internal.PostHogPreferences] store: that store doubles as the
 * registered-properties store (any non-internal key rides along on events) and
 * is cleared by `reset()`, while this watermark is device-level dedup state
 * that must never appear on events and must survive identity changes.
 */
internal class NativeCrashWatermarkStore(context: Context) {
    private val preferences =
        context.getSharedPreferences("posthog-native-crash", Context.MODE_PRIVATE)

    fun get(): Long = preferences.getLong(KEY, 0L)

    // commit() rather than apply(): advancing the watermark acknowledges a crash
    // record as reported forever, so the write must be on disk before the record
    // is skipped on the next scan. apply() writes asynchronously and a process
    // death could lose the acknowledgement.
    @SuppressLint("ApplySharedPref")
    fun advance(timestamp: Long) {
        preferences.edit().putLong(KEY, timestamp).commit()
    }

    private companion object {
        private const val KEY = "lastCapturedTimestamp"
    }
}

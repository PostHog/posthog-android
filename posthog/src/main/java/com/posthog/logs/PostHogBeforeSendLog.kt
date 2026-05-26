package com.posthog.logs

/**
 * Hook that runs on every captured log record before it is enqueued.
 *
 * Return `null` to drop the record, return the (possibly mutated) record to
 * keep it. Multiple hooks compose left-to-right; if any returns `null`,
 * later hooks are skipped. Registered via
 * [PostHogLogsConfig.addBeforeSend].
 *
 * Runs synchronously on the thread that called the logger — keep work fast
 * (no network, no disk).
 *
 * ### Kotlin (SAM)
 *
 * ```kotlin
 * config.logs.addBeforeSend { record ->
 *     if (record.level == PostHogLogSeverity.TRACE) null else record
 * }
 * ```
 *
 * ### Java
 *
 * ```java
 * config.getLogs().addBeforeSend(record -> {
 *     if (record.getLevel() == PostHogLogSeverity.TRACE) return null;
 *     return record;
 * });
 * ```
 *
 * @see PostHogLogsConfig.addBeforeSend
 * @see PostHogLogsConfig.removeBeforeSend
 */
public fun interface PostHogBeforeSendLog {
    /**
     * Called once per captured record. Return the record (possibly with a
     * mutated copy via [PostHogLogRecord.copy]) to keep it, or `null` to
     * drop it.
     */
    public fun run(record: PostHogLogRecord): PostHogLogRecord?
}

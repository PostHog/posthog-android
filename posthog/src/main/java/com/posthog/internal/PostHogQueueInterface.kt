package com.posthog.internal

public interface PostHogQueueInterface<Record> {
    public fun add(record: Record)

    public fun flush()

    public fun start()

    public fun stop()

    public fun clear()
}

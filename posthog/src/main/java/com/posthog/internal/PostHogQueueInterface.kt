package com.posthog.internal

import com.posthog.PostHogInternal

/**
 * Internal queue contract shared by the events, replay, and logs pipelines.
 *
 * **Not part of the public API.** Visible only because of the multi-module
 * architecture. The shape may change between SDK versions without a
 * deprecation cycle — do not implement or call this directly from outside
 * the SDK.
 */
@PostHogInternal
public interface PostHogQueueInterface<Record> {
    public fun add(record: Record)

    public fun flush()

    public fun start()

    public fun stop()

    public fun clear()
}

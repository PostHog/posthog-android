package com.posthog.internal

import com.posthog.PostHogEvent

public interface PostHogQueueInterface {
    public fun add(event: PostHogEvent)

    public fun flush()

    public fun start()

    public fun stop()

    public fun clear()
}

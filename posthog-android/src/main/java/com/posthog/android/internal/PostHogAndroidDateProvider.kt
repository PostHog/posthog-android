package com.posthog.android.internal

import com.instacart.truetime.time.TrueTimeImpl
import com.posthog.internal.PostHogDateProvider
import java.util.Date

internal class PostHogAndroidDateProvider : PostHogDateProvider {
    private val client = TrueTimeImpl()

    init {
        client.sync()
    }

    override fun currentDate(): Date {
        return client.now()
    }

    override fun addSecondsToCurrentDate(seconds: Int): Date {
        return Date(client.now().time + (seconds * 1000))
    }

    override fun currentTimeMillis(): Long {
        return client.now().time
    }

    override fun nanoTime(): Long {
        return System.nanoTime()
    }
}

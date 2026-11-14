package com.posthog.android.errortracking

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

// Runs submitted work inline so scans and consent writes complete during the call
internal open class DirectExecutorService : AbstractExecutorService() {
    private var shutdown = false

    override fun execute(command: Runnable) = command.run()

    override fun shutdown() {
        shutdown = true
    }

    override fun shutdownNow(): MutableList<Runnable> {
        shutdown = true
        return mutableListOf()
    }

    override fun isShutdown(): Boolean = shutdown

    override fun isTerminated(): Boolean = shutdown

    override fun awaitTermination(
        timeout: Long,
        unit: TimeUnit,
    ): Boolean = true
}

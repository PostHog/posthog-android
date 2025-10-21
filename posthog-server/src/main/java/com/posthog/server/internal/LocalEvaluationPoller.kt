package com.posthog.server.internal

import com.posthog.PostHogConfig
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Poller for periodically fetching feature flag definitions for local evaluation
 */
internal class LocalEvaluationPoller(
    private val config: PostHogConfig,
    private val pollIntervalSeconds: Int,
    private val execute: () -> Unit,
) {
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "PostHog-LocalEvaluationPoller").apply {
                isDaemon = true
            }
        }

    private var isStarted = false

    fun start() {
        if (isStarted) {
            config.logger.log("LocalEvaluationPoller already started")
            return
        }

        isStarted = true
        config.logger.log("Starting LocalEvaluationPoller with interval ${pollIntervalSeconds}s")

        // Schedule the task to run periodically
        executor.scheduleAtFixedRate(
            {
                try {
                    execute()
                } catch (e: Throwable) {
                    config.logger.log("Error in LocalEvaluationPoller: ${e.message}")
                }
            },
            0,
            pollIntervalSeconds.toLong(),
            TimeUnit.SECONDS,
        )
    }

    fun stop() {
        if (!isStarted) {
            return
        }

        config.logger.log("Stopping LocalEvaluationPoller")
        isStarted = false

        executor.shutdown()
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }
}

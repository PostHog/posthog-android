package com.posthog.server

/**
 * Forked-JVM fixture for [PostHogUncaughtExceptionSubprocessTest]: a real client with uncaught
 * capture enabled crashing for real, so process exit, worker-thread survival and stderr output can
 * be asserted on an actual JVM instead of a directly invoked handler.
 *
 * Args: `<ingestion host> <scenario>` where scenario is `main-crash` or `worker-crash`.
 */
public object CrashFixture {
    @JvmStatic
    public fun main(args: Array<String>) {
        val host = args[0]
        val scenario = args[1]

        val postHog =
            PostHog.with(
                PostHogConfig.builder("fixture_api_key")
                    .host(host)
                    // A worker-thread capture takes the regular async path; a threshold of one lets
                    // the fixture deliver it without waiting for the periodic flush.
                    .flushAt(1)
                    .captureUncaughtExceptions(true)
                    .build(),
            )

        when (scenario) {
            // The real main thread dies: the handler must deliver the fatal event before the JVM
            // exits (code 1) and reproduce the default crash banner on stderr.
            "main-crash" -> throw IllegalStateException("fixture main crash")

            // Only the worker dies: the process must survive, the capture is level error, and the
            // fixture exits 0 after flushing.
            "worker-crash" -> {
                val worker = Thread { throw IllegalStateException("fixture worker crash") }
                worker.name = "fixture-worker"
                worker.start()
                worker.join()
                // The capture is enqueued asynchronously; give the queue executor a moment before
                // the inline flush so the event is visible to it.
                Thread.sleep(500)
                postHog.flush()
                println("WORKER_SURVIVED")
            }

            else -> error("unknown scenario: $scenario")
        }
    }
}

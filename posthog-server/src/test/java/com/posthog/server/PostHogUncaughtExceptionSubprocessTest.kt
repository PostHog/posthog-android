package com.posthog.server

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Forked-JVM tests via [CrashFixture]: the in-process suite invokes the handler directly, which
 * cannot prove real process exit, worker-thread survival, or the stderr the JVM actually emits.
 */
internal class PostHogUncaughtExceptionSubprocessTest {
    private class FixtureRun(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val batches: List<BatchRequest>,
    )

    private fun runFixture(scenario: String): FixtureRun {
        val batches = CopyOnWriteArrayList<BatchRequest>()
        val server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path?.contains("/batch") == true) {
                        batches.add(request.parseBatch())
                    }
                    return MockResponse().setResponseCode(200)
                }
            }
        server.start()

        try {
            val javaBin = File(File(System.getProperty("java.home"), "bin"), "java").absolutePath
            val process =
                ProcessBuilder(
                    javaBin,
                    "-cp",
                    System.getProperty("java.class.path"),
                    CrashFixture::class.java.name,
                    server.url("/").toString(),
                    scenario,
                ).start()

            // Both streams stay tiny (a banner line, one stack trace), far below the pipe buffer,
            // so sequential reads cannot deadlock.
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Fixture JVM did not exit in time")

            return FixtureRun(process.exitValue(), stdout, stderr, batches.toList())
        } finally {
            server.shutdown()
        }
    }

    private fun exceptionProperties(run: FixtureRun): Map<String, Any?> {
        val batch = run.batches.firstOrNull { it.findEvent("\$exception") != null }
        assertNotNull(batch, "Expected an \$exception event to reach the server, got: ${run.batches}")
        return batch.eventProperties("\$exception")
    }

    @Test
    fun `a main-thread crash terminates the process and delivers a fatal event first`() {
        val run = runFixture("main-crash")

        // The JVM's exit code for an uncaught exception on main.
        assertEquals(1, run.exitCode, "stderr was: ${run.stderr}")

        // Installing capture must not eat the default crash output.
        assertTrue(
            run.stderr.contains("Exception in thread \"main\""),
            "Expected the default crash banner on stderr, got: ${run.stderr}",
        )
        assertTrue(
            run.stderr.contains("fixture main crash"),
            "Expected the throwable on stderr, got: ${run.stderr}",
        )

        val props = exceptionProperties(run)
        assertEquals("fatal", props["\$exception_level"])
        assertEquals("jvm.uncaught_exception_handler", props["\$exception_source"])
    }

    @Test
    fun `a worker-thread crash leaves the process running and delivers an error event`() {
        val run = runFixture("worker-crash")

        assertEquals(0, run.exitCode, "stderr was: ${run.stderr}")
        assertTrue(
            run.stdout.contains("WORKER_SURVIVED"),
            "Expected the process to keep running after the worker died, got: ${run.stdout}",
        )
        assertTrue(
            run.stderr.contains("fixture worker crash"),
            "Expected the worker's throwable on stderr, got: ${run.stderr}",
        )

        val props = exceptionProperties(run)
        assertEquals("error", props["\$exception_level"])
    }
}

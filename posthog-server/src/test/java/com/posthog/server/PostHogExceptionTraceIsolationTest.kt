package com.posthog.server

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class PostHogExceptionTraceIsolationTest {
    @Test
    fun `exceptions are captured without OpenTelemetry on the runtime classpath`() {
        val classpath =
            System.getProperty("java.class.path").split(File.pathSeparator)
                .filterNot { File(it).name.startsWith("opentelemetry-") }
                .joinToString(File.pathSeparator)
        val process =
            ProcessBuilder(
                File(System.getProperty("java.home"), "bin/java").absolutePath,
                "-cp",
                classpath,
                NoTracingFixture::class.java.name,
            ).redirectErrorStream(true).start()
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Fixture JVM did not exit")
            assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().readText())
        } finally {
            process.destroyForcibly()
        }
    }
}

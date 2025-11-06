package com.posthog.internal.errortracking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class ThrowableCoercerTest {
    private val coercer = ThrowableCoercer()

    @Test
    fun `filename uses fileName when available`() {
        val exception =
            createExceptionWithStackTrace(
                className = "com.example.MyClass",
                methodName = "myMethod",
                fileName = "MyClass.java",
                lineNumber = 42,
            )

        val result = coercer.fromThrowableToPostHogProperties(exception)

        val frames = getFrames(result)
        assertEquals("MyClass.java", frames[0]["filename"])
    }

    @Test
    fun `filename uses className and methodName when fileName is null`() {
        val exception =
            createExceptionWithStackTrace(
                className = "com.example.MyClass",
                methodName = "myMethod",
                fileName = null,
                lineNumber = -1,
            )

        val result = coercer.fromThrowableToPostHogProperties(exception)

        val frames = getFrames(result)
        assertEquals("com.example.MyClass.myMethod", frames[0]["filename"])
    }

    @Test
    fun `filename uses only className when methodName is null`() {
        val exception =
            createExceptionWithStackTrace(
                className = "com.example.MyClass",
                methodName = null,
                fileName = null,
                lineNumber = -1,
            )

        val result = coercer.fromThrowableToPostHogProperties(exception)

        val frames = getFrames(result)
        assertEquals("com.example.MyClass", frames[0]["filename"])
    }

    @Test
    fun `filename uses only methodName when className is null`() {
        val exception =
            createExceptionWithStackTrace(
                className = null,
                methodName = "myMethod",
                fileName = null,
                lineNumber = -1,
            )

        val result = coercer.fromThrowableToPostHogProperties(exception)

        val frames = getFrames(result)
        assertEquals("myMethod", frames[0]["filename"])
    }

    @Test
    fun `filename uses only methodName when className is empty`() {
        val exception =
            createExceptionWithStackTrace(
                className = "",
                methodName = "myMethod",
                fileName = null,
                lineNumber = -1,
            )

        val result = coercer.fromThrowableToPostHogProperties(exception)

        val frames = getFrames(result)
        assertEquals("myMethod", frames[0]["filename"])
    }

    @Test
    fun `filename is null when both className and methodName are null`() {
        val exception =
            createExceptionWithStackTrace(
                className = null,
                methodName = null,
                fileName = null,
                lineNumber = -1,
            )

        val result = coercer.fromThrowableToPostHogProperties(exception)

        val frames = getFrames(result)
        assertNull(frames[0]["filename"])
    }

    // Helper function to create an exception with a specific stack trace
    private fun createExceptionWithStackTrace(
        className: String?,
        methodName: String?,
        fileName: String?,
        lineNumber: Int,
    ): Throwable {
        val exception = RuntimeException("Test exception")
        val stackTraceElement =
            StackTraceElement(
                className ?: "",
                methodName ?: "",
                fileName,
                lineNumber,
            )
        exception.stackTrace = arrayOf(stackTraceElement)
        return exception
    }

    // Helper function to extract frames from the result
    @Suppress("UNCHECKED_CAST")
    private fun getFrames(result: Map<String, Any>): List<Map<String, Any>> {
        val exceptionList = result["\$exception_list"] as List<Map<String, Any>>
        val exception = exceptionList[0]
        val stackTrace = exception["stacktrace"] as Map<String, Any>
        return stackTrace["frames"] as List<Map<String, Any>>
    }
}

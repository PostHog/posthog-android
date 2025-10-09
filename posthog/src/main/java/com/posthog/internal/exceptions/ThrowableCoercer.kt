package com.posthog.internal.exceptions

internal class ThrowableCoercer {
    private fun isInApp(
        className: String,
        inAppIncludes: List<String>,
    ): Boolean {
        inAppIncludes.forEach { include ->
            if (className.startsWith(include)) {
                return true
            }
        }

        return false
    }

    fun fromThrowableToPostHogProperties(
        throwable: Throwable,
        inAppIncludes: List<String> = listOf(),
        handled: Boolean = true,
        isFatal: Boolean = false,
    ): MutableMap<String, Any> {
        val exceptions = mutableListOf<Map<String, Any>>()

        val allThrowables = mutableListOf<Throwable>()
        val circularDetector = hashSetOf<Throwable>()

        var currentThrowable: Throwable? = throwable
        while (currentThrowable != null && circularDetector.add(currentThrowable)) {
            allThrowables.add(currentThrowable)

            currentThrowable = currentThrowable.cause
        }

        allThrowables.forEach { theThrowable ->
            val thePackage = theThrowable.javaClass.`package`
            val theClass = theThrowable.javaClass.name
            val className = if (thePackage != null) theClass.replace(thePackage.name + ".", "") else theClass
            val exceptionPackage = thePackage?.name

            val stackTraces = theThrowable.stackTrace

            val stackTrace = mutableMapOf<String, Any?>()
            if (stackTraces.isNotEmpty()) {
                val frames = mutableListOf<Map<String, Any>>()

                stackTraces.forEach { frame ->
                    val myFrame = mutableMapOf<String, Any>()

                    myFrame["module"] = frame.className
                    myFrame["function"] = frame.methodName
                    myFrame["platform"] = "java"

                    if (frame.lineNumber >= 0) {
                        myFrame["lineno"] = frame.lineNumber
                    }

                    val fileName = frame.fileName
                    if (fileName?.isNotEmpty() == true) {
                        myFrame["filename"] = fileName
                    }

                    myFrame["in_app"] = isInApp(frame.className, inAppIncludes)

                    frames.add(myFrame)
                }

                if (frames.isNotEmpty()) {
                    stackTrace["frames"] = frames
                    stackTrace["type"] = "raw"
                }
            }

            // TODO: exception_id and parent_id
            val exception =
                mutableMapOf(
                    "type" to className,
                    "mechanism" to
                        mapOf(
                            "handled" to handled,
                            "synthetic" to false,
                            "type" to "generic",
                        ),
                    "thread_id" to Thread.currentThread().id,
                )
            if (throwable.message?.isNotEmpty() == true) {
                exception["value"] = throwable.message
            }

            if (exceptionPackage?.isNotEmpty() == true) {
                exception["module"] = exceptionPackage
            }

            if (stackTrace.isNotEmpty()) {
                exception["stacktrace"] = stackTrace
            }

            exceptions.add(exception)
        }

        val exceptionProperties =
            mutableMapOf<String, Any>(
                "\$exception_level" to if (isFatal) "fatal" else "error",
            )

        if (exceptions.isNotEmpty()) {
            exceptionProperties["\$exception_list"] = exceptions
        }

        return exceptionProperties
    }
}

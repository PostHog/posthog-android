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
        val thePackage = throwable.javaClass.`package`
        val theClass = throwable.javaClass.name
        val className = if (thePackage != null) theClass.replace(thePackage.name + ".", "") else theClass
        val exceptionPackage = thePackage?.name

        val stackTraces = throwable.stackTrace

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
                stackTrace["type"] =  "raw"
            }
        }

        val exception =
            mutableMapOf(
                "type" to className,
                "value" to throwable.message,
                "mechanism" to
                    mapOf(
                        "handled" to handled,
                        "synthetic" to false,
                    ),
                "module" to exceptionPackage,
            )

        if (stackTrace.isNotEmpty()) {
            exception["stacktrace"] = stackTrace
        }

        val exceptionProperties =
            mutableMapOf(
                "\$exception_level" to if (isFatal) "fatal" else "error",
                "\$exception_list" to listOf(exception),
            )

        return exceptionProperties
    }
}

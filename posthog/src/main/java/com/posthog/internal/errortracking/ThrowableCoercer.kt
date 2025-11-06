package com.posthog.internal.errortracking

import com.posthog.PostHogInternal

@PostHogInternal
public class ThrowableCoercer {
    private fun isInApp(
        className: String,
        inAppIncludes: List<String>,
    ): Boolean {
        // if there's nothing, all frames are considered in app
        if (inAppIncludes.isEmpty()) {
            return true
        }

        inAppIncludes.forEach { include ->
            if (className.startsWith(include)) {
                return true
            }
        }

        return false
    }

    public fun fromThrowableToPostHogProperties(
        throwable: Throwable,
        inAppIncludes: List<String> = listOf(),
    ): MutableMap<String, Any> {
        val exceptions = mutableListOf<Map<String, Any>>()
        val throwableList = mutableListOf<Throwable>()
        val circularDetector = hashSetOf<Throwable>()

        var handled = true
        var isFatal = false
        var mechanismType = "generic"

        var currentThrowable: Throwable? = throwable
        val threadId: Long

        if (throwable is PostHogThrowable) {
            handled = throwable.handled
            isFatal = throwable.isFatal
            mechanismType = throwable.mechanism
            currentThrowable = throwable.cause
            threadId = throwable.thread.id
        } else {
            threadId = Thread.currentThread().id
        }

        while (currentThrowable != null && circularDetector.add(currentThrowable)) {
            throwableList.add(currentThrowable)
            currentThrowable = currentThrowable.cause
        }

        throwableList.forEach { theThrowable ->
            val thePackage = theThrowable.javaClass.`package`
            val theClass = theThrowable.javaClass.name
            val className = if (thePackage != null) theClass.replace(thePackage.name + ".", "") else theClass
            val exceptionPackage = thePackage?.name

            val stackTraces = theThrowable.stackTrace

            val stackTrace = mutableMapOf<String, Any>()
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
                            "type" to mechanismType,
                        ),
                    "thread_id" to threadId,
                )

            if (theThrowable.message?.isNotEmpty() == true) {
                exception["value"] = theThrowable.message
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
                EXCEPTION_LEVEL_ATTRIBUTE to if (isFatal) EXCEPTION_LEVEL_FATAL else "error",
            )

        if (exceptions.isNotEmpty()) {
            exceptionProperties["\$exception_list"] = exceptions
        }

        return exceptionProperties
    }

    internal companion object {
        const val EXCEPTION_LEVEL_FATAL = "fatal"
        const val EXCEPTION_LEVEL_ATTRIBUTE = "\$exception_level"
    }
}

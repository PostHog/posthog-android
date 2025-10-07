package com.posthog.internal.exceptions

internal class ThrowableCoercer {
    fun fromThrowableToPostHogProperties(throwable: Throwable): MutableMap<String, Any> {
        val thePackage = throwable.javaClass.`package`
        val theClass = throwable.javaClass.name
        val className = if (thePackage != null) theClass.replace(thePackage.name + ".", "") else theClass

        val exceptionProperties =
            mutableMapOf(
                "\$exception_level" to "error",
                "\$exception_list" to
                    mutableListOf(
                        mutableMapOf(
                            "type" to className,
                            "value" to throwable.message,
                            "mechanism" to
                                mutableMapOf(
                                    "handled" to true,
                                    "synthetic" to false,
                                ),
                        ),
                    ),
            )

        return exceptionProperties
    }
}

package com.posthog.internal.errortracking

internal interface UncaughtExceptionHandlerAdapter {
    fun getDefaultUncaughtExceptionHandler(): Thread.UncaughtExceptionHandler?

    fun setDefaultUncaughtExceptionHandler(exceptionHandler: Thread.UncaughtExceptionHandler?)

    class Adapter private constructor() : UncaughtExceptionHandlerAdapter {
        companion object {
            fun getInstance(): UncaughtExceptionHandlerAdapter = INSTANCE

            private val INSTANCE = Adapter()
        }

        override fun getDefaultUncaughtExceptionHandler(): Thread.UncaughtExceptionHandler? {
            return Thread.getDefaultUncaughtExceptionHandler()
        }

        override fun setDefaultUncaughtExceptionHandler(exceptionHandler: Thread.UncaughtExceptionHandler?) {
            Thread.setDefaultUncaughtExceptionHandler(exceptionHandler)
        }
    }
}

package com.posthog.internal

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

private fun isRequestCanceled(throwable: Throwable): Boolean {
    return throwable is IOException &&
        throwable.message?.contentEquals("Canceled") ?: false || throwable is InterruptedIOException
}

private fun noInternetAvailable(throwable: Throwable): Boolean {
    return throwable is UnknownHostException
}

private fun isConnectionTimeout(throwable: Throwable): Boolean {
    return throwable is SocketTimeoutException
}

internal fun Throwable.isNetworkingError(): Boolean {
    return isConnectionTimeout(this) ||
        noInternetAvailable(this) ||
        isRequestCanceled(this)
}

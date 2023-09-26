package com.posthog.internal

import com.posthog.PostHogConfig
import java.io.File
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

internal fun File.deleteSafely(config: PostHogConfig) {
    try {
        delete()
    } catch (e: Throwable) {
        config.logger.log("Error deleting the file $name: $e.")
    }
}

internal fun File.existsSafely(config: PostHogConfig): Boolean {
    return try {
        exists()
    } catch (e: Throwable) {
        config.logger.log("Error deleting the file $name: $e.")
        false
    }
}

package com.posthog.internal

import com.posthog.PostHogConfig
import com.posthog.PostHogInternal
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.SortedMap
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService

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

@PostHogInternal
public fun Throwable.isNetworkingError(): Boolean {
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

@PostHogInternal
public fun ExecutorService.submitSyncSafely(run: Runnable) {
    try {
        // can throw RejectedExecutionException, InterruptedException and more
        submit(run).get()
    } catch (ignored: Throwable) {
    }
}

@PostHogInternal
public fun Executor.executeSafely(run: Runnable) {
    try {
        // can throw RejectedExecutionException
        execute(run)
    } catch (ignored: Throwable) {
    }
}

@PostHogInternal
public fun Thread.interruptSafely() {
    try {
        interrupt()
    } catch (e: Throwable) {
        // ignore
    }
}

/**
 * Recursively sorts a map by keys at all levels.
 * This is useful for creating deterministic string representations of maps.
 */
internal fun sortMapRecursively(map: Map<String, Any?>): SortedMap<String, Any?> {
    val sortedMap = sortedMapOf<String, Any?>()
    for ((key, value) in map) {
        sortedMap[key] = sortValueRecursively(value)
    }
    return sortedMap
}

/**
 * Recursively sorts values that are maps, lists, or arrays containing maps.
 */
private fun sortValueRecursively(value: Any?): Any? {
    return when (value) {
        is Map<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            val stringKeyMap = value.filterKeys { it is String } as Map<String, Any?>
            sortMapRecursively(stringKeyMap)
        }
        is List<*> -> value.map { item -> sortValueRecursively(item) }
        is Array<*> -> value.map { item -> sortValueRecursively(item) }
        else -> value
    }
}

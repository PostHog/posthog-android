package com.posthog.internal

import okhttp3.ResponseBody
import java.lang.RuntimeException

/**
 * The API exception if not within the successful range (2xx - 3xx)
 * @property statusCode the HTTP status code
 * @property message the exception message
 * @property message the OkHttp response body, the source might be closed already
 */
internal class PostHogApiError(
    val statusCode: Int,
    override val message: String,
    val body: ResponseBody?,
) : RuntimeException(message)

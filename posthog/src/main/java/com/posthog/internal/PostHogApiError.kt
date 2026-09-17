package com.posthog.internal

import com.posthog.PostHogInternal
import okhttp3.ResponseBody

/**
 * The API exception if not within the successful range (2xx - 3xx)
 * @property statusCode the HTTP status code
 * @property message the exception message
 * @property message the OkHttp response body, the source might be closed already
 * @property errorCode the `code` field of a PostHog JSON error body, when the response carried one
 */
@PostHogInternal
public class PostHogApiError(
    public val statusCode: Int,
    override val message: String,
    public val body: ResponseBody?,
    public val retryAfterSeconds: Int? = null,
    public val errorCode: String? = null,
) : RuntimeException(message) {
    override fun toString(): String {
        return "PostHogApiError(statusCode=$statusCode, message='$message')"
    }
}

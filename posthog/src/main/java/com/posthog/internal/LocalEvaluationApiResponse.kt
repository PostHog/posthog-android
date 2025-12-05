package com.posthog.internal

import com.posthog.PostHogInternal

/**
 * Response from the local evaluation API that includes ETag for conditional requests.
 * Use the companion object factory methods to construct instances.
 */
@PostHogInternal
public class LocalEvaluationApiResponse private constructor(
    /**
     * The local evaluation result. Null when [wasModified] is false (304 response) or on error.
     */
    public val result: LocalEvaluationResponse?,
    /**
     * The ETag value from the response, if present.
     */
    public val etag: String?,
    /**
     * True if the server returned new data (200 OK). False if 304 Not Modified.
     */
    public val wasModified: Boolean,
) {
    public companion object {
        /**
         * Creates a response for when the server returns 304 Not Modified.
         * @param etag The ETag to preserve for the next request.
         */
        public fun notModified(etag: String?): LocalEvaluationApiResponse =
            LocalEvaluationApiResponse(result = null, etag = etag, wasModified = false)

        /**
         * Creates a response for a successful fetch with new data.
         * @param result The evaluation result from the server.
         * @param etag The ETag from the response.
         */
        public fun success(
            result: LocalEvaluationResponse?,
            etag: String?,
        ): LocalEvaluationApiResponse = LocalEvaluationApiResponse(result = result, etag = etag, wasModified = true)
    }
}

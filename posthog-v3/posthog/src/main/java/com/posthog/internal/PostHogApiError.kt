package com.posthog.internal

import okhttp3.ResponseBody
import java.lang.RuntimeException

internal class PostHogApiError(
    val statusCode: Int,
    override val message: String,
    body: ResponseBody? = null,
) : RuntimeException(message)

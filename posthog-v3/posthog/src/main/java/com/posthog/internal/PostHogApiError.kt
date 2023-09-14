package com.posthog.internal

import okhttp3.ResponseBody
import java.lang.RuntimeException

// TODO: rete limit will be part of response in the future
internal class PostHogApiError(
    val statusCode: Int,
    override val message: String,
    body: ResponseBody? = null,
) : RuntimeException(message)

package com.posthog.internal

import java.lang.RuntimeException

// TODO: rete limit will be part of response in the future
internal data class PostHogApiError(val statusCode: Int, override val message: String) : RuntimeException(message)

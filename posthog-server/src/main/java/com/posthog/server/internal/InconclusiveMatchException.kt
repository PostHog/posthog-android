package com.posthog.server.internal

/**
 * Exception thrown when flag evaluation cannot be determined locally
 */
internal class InconclusiveMatchException(message: String) : Exception(message)

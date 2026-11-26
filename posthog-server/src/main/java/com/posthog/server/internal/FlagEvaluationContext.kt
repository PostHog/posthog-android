package com.posthog.server.internal

/**
 * The top-level inputs of one local flag evaluation. A flag dependency is evaluated from them like
 * a standalone flag, with its own aggregation, bucketing identifier and properties.
 */
internal class FlagEvaluationContext(
    val distinctId: String,
    val groups: Map<String, String>?,
    val personProperties: Map<String, Any?>,
    val groupProperties: Map<String, Map<String, Any?>>?,
    val knownAbsent: KnownAbsentPropertyKeys?,
    val groupTypeMapping: Map<String, String>?,
)

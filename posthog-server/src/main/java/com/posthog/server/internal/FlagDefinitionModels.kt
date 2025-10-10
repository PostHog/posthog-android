package com.posthog.server.internal

import com.google.gson.annotations.SerializedName

/**
 * Response from /api/feature_flag/local_evaluation/
 */
internal data class LocalEvaluationResponse(
    val flags: List<FlagDefinition>?,
    @SerializedName("group_type_mapping")
    val groupTypeMapping: Map<String, String>?,
    val cohorts: Map<String, CohortDefinition>?,
)

/**
 * Complete feature flag definition for local evaluation
 */
internal data class FlagDefinition(
    val id: Int,
    val name: String,
    val key: String,
    val active: Boolean,
    val filters: FlagFilters,
    val version: Int,
)

/**
 * Flag filters containing groups and multivariate config
 */
internal data class FlagFilters(
    val groups: List<FlagConditionGroup>?,
    val multivariate: MultiVariateConfig?,
    val payloads: Map<String, Any?>?,
)

/**
 * A condition group with properties and rollout percentage
 */
internal data class FlagConditionGroup(
    val properties: List<FlagProperty>?,
    @SerializedName("rollout_percentage")
    val rolloutPercentage: Int?,
    val variant: String?,
)

/**
 * A property condition for flag evaluation
 */
internal data class FlagProperty(
    val key: String,
    @SerializedName("value")
    val propertyValue: Any?,
    @SerializedName("operator")
    val propertyOperator: PropertyOperator?,
    val type: PropertyType?,
    val negation: Boolean?,
    @SerializedName("dependency_chain")
    val dependencyChain: List<String>?,
)

/**
 * Multivariate configuration for A/B testing
 */
internal data class MultiVariateConfig(
    val variants: List<VariantDefinition>?,
)

/**
 * A variant definition with key and rollout percentage
 */
internal data class VariantDefinition(
    val key: String,
    @SerializedName("rollout_percentage")
    val rolloutPercentage: Double,
)

/**
 * Cohort definition for matching cohort properties
 */
internal data class CohortDefinition(
    val type: String?,
    val values: List<Any>?,
)

/**
 * Exception thrown when flag evaluation cannot be determined locally
 */
internal class InconclusiveMatchException(message: String) : Exception(message)

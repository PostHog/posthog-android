package com.posthog.internal

import com.google.gson.annotations.SerializedName
import com.posthog.PostHogInternal

/**
 * Response from /api/feature_flag/local_evaluation/
 */
@PostHogInternal
public data class LocalEvaluationResponse(
    val flags: List<FlagDefinition>?,
    @SerializedName("group_type_mapping")
    val groupTypeMapping: Map<String, String>?,
    val cohorts: Map<String, PropertyGroup>?,
)

/**
 * Complete feature flag definition for local evaluation
 */
@PostHogInternal
public class FlagDefinition(
    public val id: Int,
    public val name: String,
    public val key: String,
    public val active: Boolean,
    public val filters: FlagFilters,
    public val version: Int,
    @SerializedName("ensure_experience_continuity")
    public val ensureExperienceContinuity: Boolean = false,
)

/**
 * Flag filters containing groups and multivariate config
 */
@PostHogInternal
public class FlagFilters(
    public val groups: List<FlagConditionGroup>?,
    public val multivariate: MultiVariateConfig?,
    public val payloads: Map<String, Any?>?,
    @SerializedName("aggregation_group_type_index")
    public val aggregationGroupTypeIndex: Int?,
)

/**
 * A condition group with properties and rollout percentage
 */
@PostHogInternal
public class FlagConditionGroup(
    public val properties: List<FlagProperty>?,
    @SerializedName("rollout_percentage")
    public val rolloutPercentage: Int?,
    public val variant: String?,
)

/**
 * A property condition for flag evaluation
 */
@PostHogInternal
public data class FlagProperty(
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
@PostHogInternal
public data class MultiVariateConfig(
    val variants: List<VariantDefinition>?,
)

/**
 * A variant definition with key and rollout percentage
 */
@PostHogInternal
public class VariantDefinition(
    public val key: String,
    @SerializedName("rollout_percentage")
    public val rolloutPercentage: Double,
)

@PostHogInternal
public enum class LogicalOperator {
    AND,
    OR,
}

@PostHogInternal
public sealed interface PropertyValue {
    public fun isEmpty(): Boolean

    public data class PropertyGroups(val values: List<PropertyGroup>) : PropertyValue {
        override fun isEmpty(): Boolean = values.isEmpty()
    }

    public data class FlagProperties(val values: List<FlagProperty>) : PropertyValue {
        override fun isEmpty(): Boolean = values.isEmpty()
    }
}

/**
 * Property group definition for matching property values
 */
@PostHogInternal
public data class PropertyGroup(
    val type: LogicalOperator?,
    val values: PropertyValue?,
)

@PostHogInternal
public enum class PropertyOperator {
    UNKNOWN,
    EXACT,
    IS_NOT,
    IS_SET,
    IS_NOT_SET,
    ICONTAINS,
    NOT_ICONTAINS,
    REGEX,
    NOT_REGEX,
    IN,
    GT,
    GTE,
    LT,
    LTE,
    IS_DATE_BEFORE,
    IS_DATE_AFTER,
    FLAG_EVALUATES_TO,
    ;

    public companion object {
        public fun fromString(value: String): PropertyOperator {
            return when (value) {
                "exact" -> EXACT
                "is_not" -> IS_NOT
                "is_set" -> IS_SET
                "is_not_set" -> IS_NOT_SET
                "icontains" -> ICONTAINS
                "not_icontains" -> NOT_ICONTAINS
                "regex" -> REGEX
                "not_regex" -> NOT_REGEX
                "in" -> IN
                "gt" -> GT
                "gte" -> GTE
                "lt" -> LT
                "lte" -> LTE
                "is_date_before" -> IS_DATE_BEFORE
                "is_date_after" -> IS_DATE_AFTER
                "flag_evaluates_to" -> FLAG_EVALUATES_TO
                else -> UNKNOWN
            }
        }

        public fun fromStringOrNull(str: String?): PropertyOperator? {
            return str?.let { fromString(it) }
        }
    }
}

@PostHogInternal
public enum class PropertyType {
    COHORT,
    FLAG,
    PERSON,
    ;

    public companion object {
        public fun fromString(value: String): PropertyType {
            return when (value) {
                "cohort" -> COHORT
                "flag" -> FLAG
                "person" -> PERSON
                else -> PERSON
            }
        }

        public fun fromStringOrNull(str: String?): PropertyType? {
            return str?.let { fromString(it) }
        }
    }
}

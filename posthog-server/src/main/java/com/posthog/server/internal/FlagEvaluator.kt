package com.posthog.server.internal

import com.posthog.PostHogConfig
import com.posthog.internal.FlagConditionGroup
import com.posthog.internal.FlagDefinition
import com.posthog.internal.FlagProperty
import com.posthog.internal.LogicalOperator
import com.posthog.internal.PropertyGroup
import com.posthog.internal.PropertyOperator
import com.posthog.internal.PropertyType
import com.posthog.internal.PropertyValue
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.regex.PatternSyntaxException

/**
 * Local evaluation engine for feature flags
 */
internal class FlagEvaluator(
    private val config: PostHogConfig,
) {
    companion object {
        private const val LONG_SCALE = 0xFFFFFFFFFFFFFFF.toDouble()
        private val NONE_VALUES_ALLOWED_OPERATORS = setOf(PropertyOperator.IS_NOT)
        private val REGEX_COMBINING_MARKS = "\\p{M}+".toRegex()
        private val REGEX_RELATIVE_DATE = "^-?([0-9]+)([hdwmy])$".toRegex()

        private val DATE_FORMATTER_WITH_SPACE_TZ =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX")
        private val DATE_FORMATTER_NO_SPACE_TZ =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssXXX")
        private val DATE_FORMATTER_NO_TZ = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        private fun casefold(input: String): String {
            val normalized = Normalizer.normalize(input, Normalizer.Form.NFD)
            return REGEX_COMBINING_MARKS.replace(normalized, "").uppercase().lowercase()
        }
    }

    private data class VariantLookupEntry(
        val key: String,
        val valueMin: Double,
        val valueMax: Double,
    )

    /**
     * Hash function for consistent rollout percentages
     * Given the same distinct_id and key, it'll always return the same float.
     * These floats are uniformly distributed between 0 and 1.
     */
    private fun hash(
        key: String,
        distinctId: String,
        salt: String = "",
    ): Double {
        val hashKey = "$key.$distinctId$salt"
        val digest = MessageDigest.getInstance("SHA-1")
        val hashBytes = digest.digest(hashKey.toByteArray(Charsets.UTF_8))

        // Take first 15 hex characters (60 bits)
        val hexString = hashBytes.joinToString("") { "%02x".format(it) }
        val hashValue = hexString.substring(0, 15).toLong(16)

        return hashValue / LONG_SCALE
    }

    /**
     * Get the matching variant for a multivariate flag
     */
    fun getMatchingVariant(
        flag: FlagDefinition,
        distinctId: String,
    ): String? {
        val hashValue = hash(flag.key, distinctId, salt = "variant")
        val variants = variantLookupTable(flag)

        for (variant in variants) {
            if (hashValue >= variant.valueMin && hashValue < variant.valueMax) {
                return variant.key
            }
        }
        return null
    }

    /**
     * Build variant lookup table for efficient variant selection. Order of the variants matters,
     * and this implementation mirrors the ordering provided by the local evaluation API.
     */
    private fun variantLookupTable(flag: FlagDefinition): List<VariantLookupEntry> {
        val lookupTable = mutableListOf<VariantLookupEntry>()
        var valueMin = 0.0

        flag.filters.multivariate?.variants?.let { variants ->
            for (variant in variants) {
                val valueMax = valueMin + (variant.rolloutPercentage / 100.0)
                lookupTable.add(
                    VariantLookupEntry(
                        key = variant.key,
                        valueMin = valueMin,
                        valueMax = valueMax,
                    ),
                )
                valueMin = valueMax
            }
        }
        return lookupTable
    }

    /**
     * Match a property condition against property values
     * Only looks for matches where key exists in propertyValues
     */
    fun matchProperty(
        property: FlagProperty,
        propertyValues: Map<String, Any?>,
    ): Boolean {
        val key = property.key
        val propertyOperator = property.propertyOperator ?: PropertyOperator.EXACT
        val propertyValue = property.propertyValue

        // Check if property key exists in values
        if (!propertyValues.containsKey(key)) {
            throw InconclusiveMatchException("Can't match properties without a given property value")
        }

        // is_not_set operator can't be evaluated locally
        if (propertyOperator == PropertyOperator.IS_NOT_SET) {
            throw InconclusiveMatchException("Can't match properties with operator is_not_set")
        }

        val overrideValue = propertyValues[key]

        // Handle null values (only allowed for certain operators)
        if (propertyOperator !in NONE_VALUES_ALLOWED_OPERATORS && overrideValue == null) {
            return false
        }

        return when (propertyOperator) {
            PropertyOperator.EXACT, PropertyOperator.IS_NOT -> {
                val matches = computeExactMatch(propertyValue, overrideValue)
                if (propertyOperator == PropertyOperator.EXACT) matches else !matches
            }

            PropertyOperator.IS_SET -> propertyValues.containsKey(key)
            PropertyOperator.ICONTAINS ->
                stringContains(
                    overrideValue.toString(),
                    propertyValue.toString(),
                    ignoreCase = true,
                )

            PropertyOperator.NOT_ICONTAINS ->
                !stringContains(
                    overrideValue.toString(),
                    propertyValue.toString(),
                    ignoreCase = true,
                )

            PropertyOperator.REGEX ->
                matchesRegex(
                    propertyValue.toString(),
                    overrideValue.toString(),
                )

            PropertyOperator.NOT_REGEX ->
                !matchesRegex(
                    propertyValue.toString(),
                    overrideValue.toString(),
                )

            PropertyOperator.GT, PropertyOperator.GTE, PropertyOperator.LT, PropertyOperator.LTE ->
                compareValues(
                    overrideValue,
                    propertyValue,
                    propertyOperator,
                )

            PropertyOperator.IS_DATE_BEFORE, PropertyOperator.IS_DATE_AFTER ->
                compareDates(
                    overrideValue,
                    propertyValue,
                    propertyOperator,
                )

            else -> throw InconclusiveMatchException("Unknown operator: $propertyOperator")
        }
    }

    private fun computeExactMatch(
        propertyValue: Any?,
        overrideValue: Any?,
    ): Boolean {
        // Lowercase to uppercase to normalize locale (e.g., Turkish i, German ß)
        // String.equals apparently does this when ignoreCase=true, but it doesn't seem to work.
        // https://kotlinlang.org/api/core/1.3/kotlin-stdlib/kotlin.text/equals.html
        val expectedValue = overrideValue?.let { casefold(it.toString()) }
        return when {
            propertyValue is List<*> -> {
                propertyValue.any { v ->
                    v == expectedValue || (v != null && casefold(v.toString()) == expectedValue)
                }
            }

            else ->
                propertyValue == expectedValue || (
                    propertyValue != null && casefold(
                        propertyValue.toString(),
                    ) == expectedValue
                )
        }
    }

    private fun stringContains(
        haystack: String,
        needle: String,
        ignoreCase: Boolean,
    ): Boolean {
        if (ignoreCase) {
            return casefold(haystack).contains(casefold(needle), ignoreCase = true)
        }
        return haystack.contains(needle)
    }

    private fun matchesRegex(
        pattern: String,
        propertyValue: String,
    ): Boolean {
        return try {
            Regex(pattern).find(propertyValue) != null
        } catch (e: PatternSyntaxException) {
            false
        }
    }

    private fun compareValues(
        overrideValue: Any?,
        propertyValue: Any?,
        propertyOperator: PropertyOperator,
    ): Boolean {
        val numericValue = propertyValue?.toString()?.toDoubleOrNull()

        return if (numericValue != null && overrideValue != null) {
            when (overrideValue) {
                is String ->
                    compareStrings(
                        overrideValue,
                        propertyValue.toString(),
                        propertyOperator,
                    )

                is Number ->
                    compareNumbers(
                        overrideValue.toDouble(),
                        numericValue,
                        propertyOperator,
                    )

                else ->
                    compareStrings(
                        overrideValue.toString(),
                        propertyValue.toString(),
                        propertyOperator,
                    )
            }
        } else {
            // String comparison if numeric parsing fails
            compareStrings(overrideValue.toString(), propertyValue.toString(), propertyOperator)
        }
    }

    private fun compareNumbers(
        lhs: Double,
        rhs: Double,
        propertyOperator: PropertyOperator,
    ): Boolean {
        return when (propertyOperator) {
            PropertyOperator.GT -> lhs > rhs
            PropertyOperator.GTE -> lhs >= rhs
            PropertyOperator.LT -> lhs < rhs
            PropertyOperator.LTE -> lhs <= rhs
            else -> false
        }
    }

    private fun compareStrings(
        lhs: String,
        rhs: String,
        propertyOperator: PropertyOperator,
    ): Boolean {
        return when (propertyOperator) {
            PropertyOperator.GT -> lhs > rhs
            PropertyOperator.GTE -> lhs >= rhs
            PropertyOperator.LT -> lhs < rhs
            PropertyOperator.LTE -> lhs <= rhs
            else -> false
        }
    }

    private fun compareDates(
        overrideValue: Any?,
        propertyValue: Any?,
        propertyOperator: PropertyOperator,
    ): Boolean {
        val parsedDate =
            try {
                parseDateValue(propertyValue.toString())
            } catch (e: Exception) {
                throw InconclusiveMatchException("The date set on the flag is not a valid format")
            }

        val overrideDate =
            when (overrideValue) {
                is Date -> overrideValue.toInstant().atZone(ZoneId.systemDefault())
                is ZonedDateTime -> overrideValue
                is Instant -> overrideValue.atZone(ZoneId.systemDefault())
                is String -> {
                    try {
                        parseOverrideDate(overrideValue)
                    } catch (e: Exception) {
                        throw InconclusiveMatchException("The date provided is not a valid format")
                    }
                }

                else -> throw InconclusiveMatchException("The date provided must be a string or date object")
            }

        return when (propertyOperator) {
            PropertyOperator.IS_DATE_BEFORE -> overrideDate.isBefore(parsedDate)
            PropertyOperator.IS_DATE_AFTER -> overrideDate.isAfter(parsedDate)
            else -> false
        }
    }

    /**
     * Parse date value from flag definition, supporting relative dates
     */
    private fun parseDateValue(propertyValue: String): ZonedDateTime {
        // Try relative date first (e.g., "-1d", "-2w", "-3m", "-1y")
        val relativeDate = parseRelativeDate(propertyValue)
        if (relativeDate != null) {
            return relativeDate
        }

        // Fall back to absolute date parsing
        return parseOverrideDate(propertyValue)
    }

    /**
     * Parse relative date format (e.g., "-1d" or "1d" for 1 day ago). Always produces a date in the past.
     */
    private fun parseRelativeDate(propertyValue: String): ZonedDateTime? {
        val match = REGEX_RELATIVE_DATE.find(propertyValue) ?: return null

        val number = match.groupValues[1].toIntOrNull() ?: return null
        val interval = match.groupValues[2]

        // From the Python SDK: avoid overflow or overly large date ranges
        if (number >= 10_000) {
            return null
        }

        val now = ZonedDateTime.now()
        return when (interval) {
            "h" -> now.minus(number.toLong(), ChronoUnit.HOURS)
            "d" -> now.minus(number.toLong(), ChronoUnit.DAYS)
            "w" -> now.minus(number.toLong(), ChronoUnit.WEEKS)
            "m" -> now.minus(number.toLong(), ChronoUnit.MONTHS)
            "y" -> now.minus(number.toLong(), ChronoUnit.YEARS)
            else -> null
        }
    }

    /**
     * Parse absolute date from string
     */
    private fun parseOverrideDate(propertyValue: String): ZonedDateTime {
        try {
            // Try ISO 8601 with timezone (standard format with 'T')
            return ZonedDateTime.parse(propertyValue)
        } catch (e: DateTimeParseException) {
            // fall through
        }

        try {
            // Try ISO_DATE_TIME
            return ZonedDateTime.parse(propertyValue, DateTimeFormatter.ISO_DATE_TIME)
        } catch (e: DateTimeParseException) {
            // fall through
        }

        try {
            // Try date only: "2022-05-01"
            return java.time.LocalDate.parse(propertyValue, DateTimeFormatter.ISO_DATE)
                .atStartOfDay(ZoneId.systemDefault())
        } catch (e: DateTimeParseException) {
            // fall through
        }

        try {
            // Try Instant (UTC)
            return Instant.parse(propertyValue).atZone(ZoneId.systemDefault())
        } catch (e: DateTimeParseException) {
            // fall through
        }

        try {
            // Try datetime with space and timezone offset: "2022-04-05 12:34:12 +01:00"
            return ZonedDateTime.parse(propertyValue, DATE_FORMATTER_WITH_SPACE_TZ)
        } catch (e: DateTimeParseException) {
            // fall through
        }

        try {
            // Try datetime with timezone offset (no space): "2022-04-05 12:34:12+01:00"
            return ZonedDateTime.parse(propertyValue, DATE_FORMATTER_NO_SPACE_TZ)
        } catch (e: DateTimeParseException) {
            // fall through
        }

        try {
            // Try datetime without timezone: "2022-05-01 00:00:00"
            return java.time.LocalDateTime.parse(propertyValue, DATE_FORMATTER_NO_TZ)
                .atZone(ZoneId.systemDefault())
        } catch (e: DateTimeParseException) {
            // All formats failed
        }

        throw DateTimeParseException("Unable to parse date: $propertyValue", propertyValue, 0)
    }

    /**
     * Match a cohort property against property values
     */
    fun matchCohort(
        property: FlagProperty,
        propertyValues: Map<String, Any?>,
        cohortProperties: Map<String, PropertyGroup>,
        flagsByKey: Map<String, FlagDefinition>?,
        evaluationCache: MutableMap<String, Any?>?,
        distinctId: String?,
    ): Boolean {
        val cohortId =
            property.propertyValue?.toString()
                ?: throw InconclusiveMatchException("Cohort property missing value")

        if (!cohortProperties.containsKey(cohortId)) {
            throw InconclusiveMatchException("Can't match cohort without a given cohort property value")
        }

        val propertyGroup =
            cohortProperties[cohortId]
                ?: throw InconclusiveMatchException("Cohort definition not found")
        return matchPropertyGroup(
            propertyGroup,
            propertyValues,
            cohortProperties,
            flagsByKey,
            evaluationCache,
            distinctId,
        )
    }

    /**
     * Match a property group (AND/OR) against property values
     */
    fun matchPropertyGroup(
        propertyGroup: PropertyGroup,
        propertyValues: Map<String, Any?>,
        cohortProperties: Map<String, PropertyGroup>,
        flagsByKey: Map<String, FlagDefinition>?,
        evaluationCache: MutableMap<String, Any?>?,
        distinctId: String?,
    ): Boolean {
        val groupType = propertyGroup.type
        val properties = propertyGroup.values

        // Empty properties always match
        if (properties == null || properties.isEmpty()) return true

        var errorMatchingLocally = false

        // Handle based on whether we have nested property groups or flag properties
        when (properties) {
            is PropertyValue.PropertyGroups -> {
                for (nestedGroup in properties.values) {
                    try {
                        val matches =
                            matchPropertyGroup(
                                nestedGroup,
                                propertyValues,
                                cohortProperties,
                                flagsByKey,
                                evaluationCache,
                                distinctId,
                            )

                        if (groupType == LogicalOperator.AND) {
                            if (!matches) return false
                        } else {
                            // OR group
                            if (matches) return true
                        }
                    } catch (e: InconclusiveMatchException) {
                        config.logger.log("Failed to compute nested property group locally: ${e.message}")
                        errorMatchingLocally = true
                    }
                }

                if (errorMatchingLocally) {
                    throw InconclusiveMatchException("Can't match cohort without a given cohort property value")
                }

                // If we get here, all matched in AND case, or none matched in OR case
                return groupType == LogicalOperator.AND
            }

            is PropertyValue.FlagProperties -> {
                // Regular properties
                for (property in properties.values) {
                    try {
                        val matches =
                            when (property.type) {
                                PropertyType.COHORT ->
                                    matchCohort(
                                        property,
                                        propertyValues,
                                        cohortProperties,
                                        flagsByKey,
                                        evaluationCache,
                                        distinctId,
                                    )

                                PropertyType.FLAG ->
                                    evaluateFlagDependency(
                                        property,
                                        flagsByKey
                                            ?: throw InconclusiveMatchException("Cannot evaluate flag dependencies without flagsByKey"),
                                        evaluationCache
                                            ?: throw InconclusiveMatchException(
                                                "Cannot evaluate flag dependencies without evaluationCache",
                                            ),
                                        distinctId
                                            ?: throw InconclusiveMatchException("Cannot evaluate flag dependencies without distinctId"),
                                        propertyValues,
                                        cohortProperties,
                                    )

                                else -> matchProperty(property, propertyValues)
                            }

                        val negation = property.negation ?: false

                        if (groupType == LogicalOperator.AND) {
                            // If negated property, do the inverse
                            if (!matches && !negation) return false
                            if (matches && negation) return false
                        } else {
                            // OR group
                            if (matches && !negation) return true
                            if (!matches && negation) return true
                        }
                    } catch (e: InconclusiveMatchException) {
                        config.logger.log("Failed to compute property ${property.key} locally: ${e.message}")
                        errorMatchingLocally = true
                    }
                }

                if (errorMatchingLocally) {
                    throw InconclusiveMatchException("Can't match cohort without a given cohort property value")
                }

                // If we get here, all matched in AND case, or none matched in OR case
                return groupType == LogicalOperator.AND
            }
        }
    }

    /**
     * Check if a condition matches for a given distinct ID
     */
    fun isConditionMatch(
        featureFlag: FlagDefinition,
        distinctId: String,
        condition: FlagConditionGroup,
        properties: Map<String, Any?>,
        cohortProperties: Map<String, PropertyGroup>,
        flagsByKey: Map<String, FlagDefinition>?,
        evaluationCache: MutableMap<String, Any?>?,
    ): Boolean {
        val rolloutPercentage = condition.rolloutPercentage
        val conditionProperties = condition.properties ?: emptyList()

        // Check all properties match
        if (conditionProperties.isNotEmpty()) {
            for (prop in conditionProperties) {
                val matches =
                    when (prop.type) {
                        PropertyType.COHORT ->
                            matchCohort(
                                prop,
                                properties,
                                cohortProperties,
                                flagsByKey,
                                evaluationCache,
                                distinctId,
                            )

                        PropertyType.FLAG ->
                            evaluateFlagDependency(
                                prop,
                                flagsByKey
                                    ?: throw InconclusiveMatchException("Cannot evaluate flag dependencies without flagsByKey"),
                                evaluationCache
                                    ?: throw InconclusiveMatchException("Cannot evaluate flag dependencies without evaluationCache"),
                                distinctId,
                                properties,
                                cohortProperties,
                            )

                        else -> matchProperty(prop, properties)
                    }

                if (!matches) {
                    return false
                }
            }

            // All properties matched, check rollout
            if (rolloutPercentage == null) {
                return true
            }
        }

        // Check rollout percentage
        if (rolloutPercentage != null && hash(
                featureFlag.key,
                distinctId,
            ) > (rolloutPercentage / 100.0)
        ) {
            return false
        }

        return true
    }

    /**
     * Main evaluation function to match feature flag properties
     * Returns the flag value (true, false, or variant key)
     */
    fun matchFeatureFlagProperties(
        flag: FlagDefinition,
        distinctId: String,
        properties: Map<String, Any?>,
        cohortProperties: Map<String, PropertyGroup> = emptyMap(),
        flagsByKey: Map<String, FlagDefinition>? = null,
        evaluationCache: MutableMap<String, Any?>? = null,
    ): Any? {
        if (flag.ensureExperienceContinuity) {
            throw InconclusiveMatchException("Flag \"${flag.key}\" has experience continuity enabled")
        }

        val flagConditions = flag.filters.groups ?: emptyList()
        var isInconclusive = false

        // Get variant keys for validation
        val flagVariants = flag.filters.multivariate?.variants ?: emptyList()
        val validVariantKeys = flagVariants.map { it.key }.toSet()

        // Sort conditions with variant overrides to the top
        // This ensures that if overrides are present, they are evaluated first
        val sortedFlagConditions =
            flagConditions.sortedBy {
                if (it.variant != null) 0 else 1
            }

        for (condition in sortedFlagConditions) {
            try {
                // If any one condition resolves to True, we can short-circuit and return the matching variant
                if (isConditionMatch(
                        flag,
                        distinctId,
                        condition,
                        properties,
                        cohortProperties,
                        flagsByKey,
                        evaluationCache,
                    )
                ) {
                    val variantOverride = condition.variant
                    val variant =
                        if (variantOverride != null && variantOverride in validVariantKeys) {
                            variantOverride
                        } else {
                            getMatchingVariant(flag, distinctId)
                        }
                    return variant ?: true
                }
            } catch (e: InconclusiveMatchException) {
                isInconclusive = true
            }
        }

        if (isInconclusive) {
            throw InconclusiveMatchException("Can't determine if feature flag is enabled or not with given properties")
        }

        // We can only return False when either all conditions are False, or no condition was inconclusive
        return false
    }

    /**
     * Evaluate a flag dependency property
     */
    fun evaluateFlagDependency(
        property: FlagProperty,
        flagsByKey: Map<String, FlagDefinition>,
        evaluationCache: MutableMap<String, Any?>,
        distinctId: String,
        properties: Map<String, Any?>,
        cohortProperties: Map<String, PropertyGroup>,
    ): Boolean {
        // Check if dependency_chain is present
        val dependencyChain = property.dependencyChain
        if (dependencyChain == null) {
            throw InconclusiveMatchException(
                "Flag dependency property for '${property.key}' is missing required 'dependency_chain' field",
            )
        }

        // Handle circular dependency (empty chain means circular)
        if (dependencyChain.isEmpty()) {
            config.logger.log("Circular dependency detected for flag: ${property.key}")
            throw InconclusiveMatchException("Circular dependency detected for flag '${property.key}'")
        }

        // Evaluate all dependencies in the chain order
        for (depFlagKey in dependencyChain) {
            if (!evaluationCache.containsKey(depFlagKey)) {
                // Need to evaluate this dependency first
                val depFlag = flagsByKey[depFlagKey]
                if (depFlag == null) {
                    // Missing flag dependency - cannot evaluate locally
                    evaluationCache[depFlagKey] = null
                    throw InconclusiveMatchException(
                        "Cannot evaluate flag dependency '$depFlagKey' - flag not found in local flags",
                    )
                } else {
                    // Check if the flag is active
                    if (!depFlag.active) {
                        evaluationCache[depFlagKey] = false
                    } else {
                        // Recursively evaluate the dependency
                        try {
                            val depResult =
                                matchFeatureFlagProperties(
                                    depFlag,
                                    distinctId,
                                    properties,
                                    cohortProperties,
                                    flagsByKey,
                                    evaluationCache,
                                )
                            evaluationCache[depFlagKey] = depResult
                        } catch (e: InconclusiveMatchException) {
                            // If we can't evaluate a dependency, store null and propagate the error
                            evaluationCache[depFlagKey] = null
                            throw InconclusiveMatchException("Cannot evaluate flag dependency '$depFlagKey': ${e.message}")
                        }
                    }
                }
            }

            // Check the cached result
            val cachedResult = evaluationCache[depFlagKey]
            if (cachedResult == null) {
                // Previously inconclusive - raise error again
                throw InconclusiveMatchException("Flag dependency '$depFlagKey' was previously inconclusive")
            } else if (cachedResult == false) {
                // Definitive False result - dependency failed
                return false
            }
        }

        // All dependencies in the chain have been evaluated successfully
        // Now check if the final flag value matches the expected value in the property
        val flagKey = property.key
        val expectedValue = property.propertyValue
        val propertyOperator = property.propertyOperator ?: PropertyOperator.EXACT

        if (expectedValue != null) {
            // Get the actual value of the flag we're checking
            val actualValue = evaluationCache[flagKey]

            if (actualValue == null) {
                // Flag wasn't evaluated - this shouldn't happen if dependency chain is correct
                throw InconclusiveMatchException("Flag '$flagKey' was not evaluated despite being in dependency chain")
            }

            // For flag dependencies, we need to compare the actual flag result with expected value
            if (propertyOperator == PropertyOperator.FLAG_EVALUATES_TO) {
                return matchesDependencyValue(expectedValue, actualValue)
            } else {
                throw InconclusiveMatchException("Flag dependency property for '${property.key}' has invalid operator '$propertyOperator'")
            }
        }

        // If no value check needed, return True (all dependencies passed)
        return true
    }

    /**
     * Check if the actual flag value matches the expected dependency value
     *
     * This follows the same logic as the C# MatchesDependencyValue function:
     * - String variant case: check for exact match or boolean true
     * - Boolean case: must match expected boolean value
     */
    private fun matchesDependencyValue(
        expectedValue: Any?,
        actualValue: Any?,
    ): Boolean {
        // String variant case - check for exact match or boolean true
        if (actualValue is String && actualValue.isNotEmpty()) {
            return when (expectedValue) {
                is Boolean -> expectedValue // Any variant matches boolean true
                is String -> actualValue == expectedValue // Variants are case-sensitive
                else -> false
            }
        }

        // Boolean case - must match expected boolean value
        if (actualValue is Boolean && expectedValue is Boolean) {
            return actualValue == expectedValue
        }

        // Default case
        return false
    }
}

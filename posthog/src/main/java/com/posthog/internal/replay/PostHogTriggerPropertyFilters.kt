package com.posthog.internal.replay

import kotlin.math.abs

// Mirrors posthog-js matchTriggerPropertyFilters and @posthog/core propertyComparisons.
internal fun matchTriggerPropertyFilters(
    filters: List<PostHogTriggerPropertyFilter>?,
    eventProperties: Map<String, Any?>?,
    personProperties: Map<String, Any?>?,
): Boolean {
    if (filters.isNullOrEmpty()) return true

    return filters.all { filter ->
        val source = if (filter.type == "person") personProperties else eventProperties
        val propertyValue = source?.get(filter.key)
        val operator = filter.operator ?: "exact"

        if (propertyValue == null) return@all operator in NEGATIVE_OPERATORS

        val comparison = PROPERTY_COMPARISONS[operator] ?: return@all false
        if (filter.value == null) return@all false

        comparison(stringifyValues(filter.value), stringifyValues(propertyValue))
    }
}

private val NEGATIVE_OPERATORS = setOf("is_not", "not_icontains", "not_regex")

private val PROPERTY_COMPARISONS: Map<String, (List<String>, List<String>) -> Boolean> =
    mapOf(
        "exact" to { targets, values -> values.any { value -> targets.any { it == value } } },
        "is_not" to { targets, values -> values.all { value -> targets.all { it != value } } },
        "regex" to { targets, values -> values.any { value -> targets.any { matchesRegex(value, it) } } },
        "not_regex" to { targets, values -> values.all { value -> targets.all { !matchesRegex(value, it) } } },
        "icontains" to { targets, values ->
            values.any { value -> targets.any { value.contains(it, ignoreCase = true) } }
        },
        "not_icontains" to { targets, values ->
            values.all { value -> targets.none { value.contains(it, ignoreCase = true) } }
        },
        "gt" to { targets, values ->
            values.any { value ->
                val n = jsParseDouble(value) ?: return@any false
                targets.any { n > (jsParseDouble(it) ?: Double.NaN) }
            }
        },
        "lt" to { targets, values ->
            values.any { value ->
                val n = jsParseDouble(value) ?: return@any false
                targets.any { n < (jsParseDouble(it) ?: Double.NaN) }
            }
        },
    )

private fun matchesRegex(
    value: String,
    pattern: String,
): Boolean =
    try {
        Regex(pattern).containsMatchIn(value)
    } catch (e: Throwable) {
        false
    }

private fun stringifyValues(value: Any): List<String> =
    when (value) {
        is List<*> -> value.map { jsToString(it) }
        else -> listOf(jsToString(value))
    }

// Gson decodes 100 as 100.0; render integral doubles like JS String() so they still compare equal.
private fun jsToString(value: Any?): String =
    when (value) {
        is Double ->
            if (value.isFinite() && value == value.toLong().toDouble() && abs(value) < 1e21) {
                value.toLong().toString()
            } else {
                value.toString()
            }
        is Float -> jsToString(value.toDouble())
        else -> value.toString()
    }

// JS parseFloat semantics: gt/lt read the leading number of values like "10items".
private fun jsParseDouble(value: String): Double? {
    val trimmed = value.trimStart()
    val match = JS_NUMBER_PREFIX.find(trimmed) ?: return null
    return match.value.toDoubleOrNull()
}

private val JS_NUMBER_PREFIX = Regex("^[+-]?(Infinity|\\d+\\.?\\d*([eE][+-]?\\d+)?|\\.\\d+([eE][+-]?\\d+)?)")

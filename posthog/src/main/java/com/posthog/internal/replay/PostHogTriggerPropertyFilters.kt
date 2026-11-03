package com.posthog.internal.replay

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

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

// Object arrays are event data too (GsonSafeMapSerializer sends them as JSON arrays), so match them like lists.
private fun stringifyValues(value: Any): List<String> =
    when (value) {
        is List<*> -> value.map { jsToString(it) }
        is Array<*> -> value.map { jsToString(it) }
        else -> listOf(jsToString(value))
    }

private fun jsToString(value: Any?): String =
    when (value) {
        is Double -> jsNumberToString(value)
        is Float -> jsNumberToString(value.toDouble())
        else -> value.toString()
    }

// JS Number.prototype.toString(): Gson decodes 100 as 100.0 and Kotlin prints 0.000001 as 1.0E-6, so lay the digits
// out with the JS plain/exponent thresholds instead ("100", "0.000001", "1e-7", "1e+21").
private fun jsNumberToString(value: Double): String {
    if (value.isNaN()) return "NaN"
    if (value.isInfinite()) return if (value < 0) "-Infinity" else "Infinity"
    if (value == 0.0) return "0"
    if (value < 0) return "-" + jsNumberToString(-value)

    // JDK < 19 (and Android's ART) don't always print the shortest round-trip digits, so find
    // the shortest decimal that reads back as this exact double before laying it out.
    val exact = BigDecimal(value)
    val text =
        (1..17)
            .asSequence()
            .map { precision -> exact.round(MathContext(precision, RoundingMode.HALF_EVEN)) }
            .first { rounded -> rounded.toString().toDouble() == value }
            .toString()

    val mantissa = text.substringBefore('E')
    val exponent = text.substringAfter('E', "0").toInt()
    val integerPart = mantissa.substringBefore('.')
    val allDigits = integerPart + mantissa.substringAfter('.', "")
    val significant = allDigits.trimStart('0')
    // The decimal point sits after `point` significant digits; negative means leading zeros after "0.".
    val point = integerPart.length + exponent - (allDigits.length - significant.length)
    val digits = significant.trimEnd('0')
    val count = digits.length

    return when {
        point in count..21 -> digits + "0".repeat(point - count)
        point in 1 until count -> digits.substring(0, point) + "." + digits.substring(point)
        point in -5..0 -> "0." + "0".repeat(-point) + digits
        else -> {
            val exponentText = if (point - 1 >= 0) "e+${point - 1}" else "e${point - 1}"
            if (count == 1) digits + exponentText else digits[0] + "." + digits.substring(1) + exponentText
        }
    }
}

// JS parseFloat semantics: gt/lt read the leading number of values like "10items".
private fun jsParseDouble(value: String): Double? {
    val trimmed = value.trimStart()
    val match = JS_NUMBER_PREFIX.find(trimmed) ?: return null
    return match.value.toDoubleOrNull()
}

private val JS_NUMBER_PREFIX = Regex("^[+-]?(Infinity|\\d+\\.?\\d*([eE][+-]?\\d+)?|\\.\\d+([eE][+-]?\\d+)?)")

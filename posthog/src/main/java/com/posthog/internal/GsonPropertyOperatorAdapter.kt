package com.posthog.internal

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import java.lang.reflect.Type

internal class GsonPropertyOperatorAdapter :
    JsonDeserializer<PropertyOperator>,
    JsonSerializer<PropertyOperator> {
    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): PropertyOperator? {
        return PropertyOperator.fromStringOrNull(json.asString)
    }

    override fun serialize(
        src: PropertyOperator?,
        typeOfSrc: Type,
        context: JsonSerializationContext,
    ): JsonElement? {
        return src?.let { JsonPrimitive(it.toApiString()) }
    }

    private fun PropertyOperator.toApiString(): String =
        when (this) {
            PropertyOperator.UNKNOWN -> "unknown"
            PropertyOperator.EXACT -> "exact"
            PropertyOperator.IS_NOT -> "is_not"
            PropertyOperator.IS_SET -> "is_set"
            PropertyOperator.IS_NOT_SET -> "is_not_set"
            PropertyOperator.ICONTAINS -> "icontains"
            PropertyOperator.NOT_ICONTAINS -> "not_icontains"
            PropertyOperator.REGEX -> "regex"
            PropertyOperator.NOT_REGEX -> "not_regex"
            PropertyOperator.IN -> "in"
            PropertyOperator.GT -> "gt"
            PropertyOperator.GTE -> "gte"
            PropertyOperator.LT -> "lt"
            PropertyOperator.LTE -> "lte"
            PropertyOperator.IS_DATE_BEFORE -> "is_date_before"
            PropertyOperator.IS_DATE_AFTER -> "is_date_after"
            PropertyOperator.FLAG_EVALUATES_TO -> "flag_evaluates_to"
            PropertyOperator.SEMVER_EQ -> "semver_eq"
            PropertyOperator.SEMVER_NEQ -> "semver_neq"
            PropertyOperator.SEMVER_GT -> "semver_gt"
            PropertyOperator.SEMVER_GTE -> "semver_gte"
            PropertyOperator.SEMVER_LT -> "semver_lt"
            PropertyOperator.SEMVER_LTE -> "semver_lte"
            PropertyOperator.SEMVER_TILDE -> "semver_tilde"
            PropertyOperator.SEMVER_CARET -> "semver_caret"
            PropertyOperator.SEMVER_WILDCARD -> "semver_wildcard"
        }
}

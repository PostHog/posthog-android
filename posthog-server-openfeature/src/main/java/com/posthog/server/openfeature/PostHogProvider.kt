package com.posthog.server.openfeature

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.posthog.server.PostHogEvaluateFlagsOptions
import com.posthog.server.PostHogInterface
import dev.openfeature.sdk.EvaluationContext
import dev.openfeature.sdk.FeatureProvider
import dev.openfeature.sdk.Metadata
import dev.openfeature.sdk.MutableStructure
import dev.openfeature.sdk.ProviderEvaluation
import dev.openfeature.sdk.Reason
import dev.openfeature.sdk.Value
import dev.openfeature.sdk.exceptions.FlagNotFoundError
import dev.openfeature.sdk.exceptions.ParseError
import dev.openfeature.sdk.exceptions.TargetingKeyMissingError
import dev.openfeature.sdk.exceptions.TypeMismatchError
import java.math.BigDecimal
import java.util.logging.Level
import java.util.logging.Logger

/**
 * OpenFeature provider backed by a configured PostHog server client.
 *
 * The caller owns the [client] lifecycle: create and configure the client (API key, personal API
 * key for local evaluation, host, ...), give it to this provider, and close it when you are done.
 * [shutdown] does not close the client.
 *
 * Evaluation context mapping:
 *   - `targetingKey` -> PostHog distinct ID
 *   - reserved attribute `groups` -> PostHog groups, keyed by group type
 *   - reserved attribute `groupProperties` -> PostHog group properties, keyed by group type
 *   - every other attribute -> PostHog person properties
 *
 * Flag type mapping:
 *   - boolean -> whether the flag is enabled
 *   - string -> the multivariate variant key
 *   - integer, long, double -> the variant key parsed as a number
 *   - object -> the JSON payload of the flag, which must be a JSON object or array
 *
 * A `$feature_flag_called` event is captured for each evaluation, unless
 * `PostHogConfig.sendFeatureFlagEvent` is false.
 *
 * @param client A configured PostHog server client.
 * @param defaultDistinctId Distinct ID to use when the evaluation context has no targeting key.
 *   When null, a missing targeting key returns the `TARGETING_KEY_MISSING` error.
 */
public class PostHogProvider
    @JvmOverloads
    constructor(
        private val client: PostHogInterface,
        private val defaultDistinctId: String? = null,
    ) : FeatureProvider {
        override fun getMetadata(): Metadata = Metadata { PROVIDER_NAME }

        override fun initialize(evaluationContext: EvaluationContext?) {
            // remote evaluation still works if the preload fails, so do not block readiness
            try {
                client.reloadFeatureFlags()
            } catch (e: Exception) {
                LOGGER.log(
                    Level.WARNING,
                    "PostHogProvider: failed to preload feature flag definitions for local evaluation; " +
                        "falling back to remote evaluation.",
                    e,
                )
            }
        }

        override fun shutdown() {
            // the caller owns the client lifecycle
        }

        override fun getBooleanEvaluation(
            key: String,
            defaultValue: Boolean?,
            ctx: EvaluationContext?,
        ): ProviderEvaluation<Boolean?> {
            val result = resolve(key, ctx)
            return result.toEvaluation(result.enabled)
        }

        override fun getStringEvaluation(
            key: String,
            defaultValue: String?,
            ctx: EvaluationContext?,
        ): ProviderEvaluation<String?> {
            val result = resolve(key, ctx)
            val variant = variantOrDefault(key, result, "string") ?: return result.toEvaluation(defaultValue)
            return result.toEvaluation(variant)
        }

        override fun getIntegerEvaluation(
            key: String,
            defaultValue: Int?,
            ctx: EvaluationContext?,
        ): ProviderEvaluation<Int?> = resolveNumber(key, defaultValue, ctx, "integer") { parseIntegral(it)?.toIntOrNull() }

        override fun getLongEvaluation(
            key: String,
            defaultValue: Long?,
            ctx: EvaluationContext?,
        ): ProviderEvaluation<Long?> = resolveNumber(key, defaultValue, ctx, "long") { parseIntegral(it)?.toLongOrNull() }

        override fun getDoubleEvaluation(
            key: String,
            defaultValue: Double?,
            ctx: EvaluationContext?,
        ): ProviderEvaluation<Double?> =
            resolveNumber(key, defaultValue, ctx, "double") {
                parseIntegral(it)?.toBigIntegerOrNull()?.toDouble() ?: it.trim().toDoubleOrNull()?.takeIf { d -> d.isFinite() }
            }

        override fun getObjectEvaluation(
            key: String,
            defaultValue: Value?,
            ctx: EvaluationContext?,
        ): ProviderEvaluation<Value?> {
            val result = resolve(key, ctx)
            val payload = result.payload
            if (payload == null) {
                if (!result.enabled) return result.toEvaluation(defaultValue)
                throw TypeMismatchError("Flag '$key' has no object payload.")
            }
            val json =
                try {
                    JsonParser.parseString(payload)
                } catch (e: Exception) {
                    throw ParseError("Flag '$key' payload is not valid JSON.", e)
                }
            if (!json.isJsonObject && !json.isJsonArray) {
                if (!result.enabled) return result.toEvaluation(defaultValue)
                throw TypeMismatchError("Flag '$key' payload is not a JSON object or array.")
            }
            return result.toEvaluation(json.toValue())
        }

        private fun <T> resolveNumber(
            key: String,
            defaultValue: T,
            ctx: EvaluationContext?,
            typeName: String,
            parse: (String) -> T?,
        ): ProviderEvaluation<T> {
            val result = resolve(key, ctx)
            val variant = variantOrDefault(key, result, typeName) ?: return result.toEvaluation(defaultValue)
            val value = parse(variant) ?: throw TypeMismatchError("Flag '$key' variant '$variant' is not a valid $typeName.")
            return result.toEvaluation(value)
        }

        /**
         * Returns the variant, or null when the flag is off so the caller gets its default value.
         * An enabled flag without a variant is a boolean flag, which is a type mismatch.
         */
        private fun variantOrDefault(
            key: String,
            result: FlagResult,
            typeName: String,
        ): String? {
            if (result.variant != null) return result.variant
            if (!result.enabled) return null
            throw TypeMismatchError("Flag '$key' has no variant to read as $typeName (boolean flag).")
        }

        private fun resolve(
            key: String,
            ctx: EvaluationContext?,
        ): FlagResult {
            val distinctId = distinctId(ctx)
            val options = PostHogEvaluateFlagsOptions.builder().flagKeys(listOf(key))
            ctx?.let { splitContext(it, options) }

            val snapshot = client.evaluateFlags(distinctId, options.build())
            if (key !in snapshot.keys) {
                throw FlagNotFoundError("Flag '$key' not found.")
            }

            // getFlag captures the $feature_flag_called event
            val value = snapshot.getFlag(key)
            val variant = value as? String
            val enabled = if (variant != null) variant.isNotEmpty() else value == true
            return FlagResult(enabled, variant, snapshot.getFlagPayload(key))
        }

        private fun distinctId(ctx: EvaluationContext?): String {
            val targetingKey = ctx?.targetingKey
            if (!targetingKey.isNullOrBlank()) return targetingKey
            return defaultDistinctId
                ?: throw TargetingKeyMissingError(
                    "The evaluation context has no targeting key and no default distinct ID is set.",
                )
        }

        private fun splitContext(
            ctx: EvaluationContext,
            options: PostHogEvaluateFlagsOptions.Builder,
        ) {
            for ((name, value) in ctx.asMap()) {
                when (name) {
                    EvaluationContext.TARGETING_KEY -> Unit
                    GROUPS_KEY ->
                        value.structureEntries()?.forEach { (type, groupKey) ->
                            groupKey.toPostHogValue()?.let { options.group(type, it.toString()) }
                        }
                    GROUP_PROPERTIES_KEY ->
                        value.structureEntries()?.forEach { (type, properties) ->
                            properties.structureEntries()?.forEach { (propertyName, propertyValue) ->
                                options.groupProperty(type, propertyName, propertyValue.toPostHogValue())
                            }
                        }
                    else -> options.personProperty(name, value.toPostHogValue())
                }
            }
        }

        private class FlagResult(
            val enabled: Boolean,
            val variant: String?,
            val payload: String?,
        )

        private fun <T> FlagResult.toEvaluation(value: T): ProviderEvaluation<T> =
            ProviderEvaluation.builder<T>()
                .value(value)
                .variant(variant)
                .reason(if (enabled) Reason.TARGETING_MATCH.name else Reason.DEFAULT.name)
                .build()

        public companion object {
            /** Name reported in the provider metadata. */
            public const val PROVIDER_NAME: String = "PostHogProvider"

            /** Reserved evaluation context attribute that holds PostHog groups, keyed by group type. */
            public const val GROUPS_KEY: String = "groups"

            /** Reserved evaluation context attribute that holds PostHog group properties, keyed by group type. */
            public const val GROUP_PROPERTIES_KEY: String = "groupProperties"

            private val LOGGER: Logger = Logger.getLogger(PostHogProvider::class.java.name)
        }
    }

private val HEX_INTEGER = Regex("0[xX][0-9a-fA-F]+")

/**
 * Parses a decimal or unsigned hexadecimal (`0x10`) integer variant, or returns null.
 */
private fun parseIntegral(variant: String): String? {
    val trimmed = variant.trim()
    if (trimmed.matches(HEX_INTEGER)) {
        return trimmed.substring(2).toBigInteger(16).toString()
    }
    return trimmed.toBigIntegerOrNull()?.toString()
}

private fun Value.structureEntries(): Map<String, Value>? = if (isStructure) asStructure().asMap() else null

private fun Value.toPostHogValue(): Any? =
    when {
        isNull -> null
        isInstant -> asInstant().toString()
        isList -> asList().map { it.toPostHogValue() }
        isStructure -> asStructure().asMap().mapValues { it.value.toPostHogValue() }
        else -> asObject()
    }

private fun JsonElement.toValue(): Value =
    when {
        isJsonNull -> Value()
        isJsonObject -> Value(MutableStructure(asJsonObject.entrySet().associate { it.key to it.value.toValue() }))
        isJsonArray -> Value(asJsonArray.map { it.toValue() })
        asJsonPrimitive.isBoolean -> Value(asBoolean)
        asJsonPrimitive.isNumber -> asBigDecimal.toNumberValue()
        else -> Value(asString)
    }

private fun BigDecimal.toNumberValue(): Value {
    val integral =
        try {
            toBigIntegerExact()
        } catch (e: ArithmeticException) {
            null
        }
    return when {
        integral == null || integral.bitLength() >= Long.SIZE_BITS -> Value(toDouble())
        integral.bitLength() < Int.SIZE_BITS -> Value(integral.toInt())
        else -> Value(integral.toLong())
    }
}

package com.posthog.internal.replay

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class PostHogTriggerPropertyFiltersTest {
    private fun filter(
        key: String = "plan",
        value: Any? = "pro",
        operator: String? = null,
        type: String? = null,
    ): PostHogTriggerPropertyFilter = PostHogTriggerPropertyFilter(key, type, operator, value)

    @Test
    fun `empty or missing filter list matches`() {
        assertTrue(matchTriggerPropertyFilters(null, mapOf("plan" to "free"), null))
        assertTrue(matchTriggerPropertyFilters(emptyList(), null, null))
    }

    @Test
    fun `exact matches case sensitively`() {
        assertTrue(matchTriggerPropertyFilters(listOf(filter()), mapOf("plan" to "pro"), null))
        assertFalse(matchTriggerPropertyFilters(listOf(filter()), mapOf("plan" to "PRO"), null))
        assertFalse(matchTriggerPropertyFilters(listOf(filter()), mapOf("plan" to "free"), null))
    }

    @Test
    fun `exact compares numbers with js string semantics`() {
        // Remote-config numbers arrive as Double (Gson) while event properties are often Int;
        // JS String(100) === String(100.0), so the string forms must match too.
        assertTrue(matchTriggerPropertyFilters(listOf(filter(key = "amount", value = 100.0)), mapOf("amount" to 100), null))
        assertTrue(matchTriggerPropertyFilters(listOf(filter(key = "amount", value = 100)), mapOf("amount" to 100.0), null))
        assertFalse(matchTriggerPropertyFilters(listOf(filter(key = "amount", value = 100.5)), mapOf("amount" to 100), null))
    }

    @Test
    fun `exact matches any element of array values`() {
        assertTrue(
            matchTriggerPropertyFilters(
                listOf(filter(value = listOf("free", "pro"))),
                mapOf("plan" to "pro"),
                null,
            ),
        )
        assertFalse(
            matchTriggerPropertyFilters(
                listOf(filter(value = listOf("free", "enterprise"))),
                mapOf("plan" to "pro"),
                null,
            ),
        )
    }

    @Test
    fun `is_not matches when the property is missing or null`() {
        assertTrue(matchTriggerPropertyFilters(listOf(filter(operator = "is_not")), null, null))
        assertTrue(matchTriggerPropertyFilters(listOf(filter(operator = "is_not")), mapOf("other" to 1), null))
        assertTrue(matchTriggerPropertyFilters(listOf(filter(operator = "is_not")), mapOf("plan" to null), null))
        assertTrue(matchTriggerPropertyFilters(listOf(filter(operator = "is_not")), mapOf("plan" to "free"), null))
        assertFalse(matchTriggerPropertyFilters(listOf(filter(operator = "is_not")), mapOf("plan" to "pro"), null))
    }

    @Test
    fun `positive operators do not match a missing property`() {
        val operators = listOf(null, "exact", "icontains", "regex", "gt", "lt")
        for (operator in operators) {
            assertFalse(
                matchTriggerPropertyFilters(listOf(filter(operator = operator)), emptyMap(), null),
                "operator $operator must not match a missing property",
            )
        }
    }

    @Test
    fun `negative operators match a missing property`() {
        for (operator in listOf("is_not", "not_icontains", "not_regex")) {
            assertTrue(
                matchTriggerPropertyFilters(listOf(filter(operator = operator)), emptyMap(), null),
                "operator $operator must match a missing property",
            )
        }
    }

    @Test
    fun `icontains is a case-insensitive substring match`() {
        assertTrue(
            matchTriggerPropertyFilters(
                listOf(filter(key = "email", operator = "icontains", value = "@CORP")),
                mapOf("email" to "marc@corp.com"),
                null,
            ),
        )
        assertFalse(
            matchTriggerPropertyFilters(
                listOf(filter(key = "email", operator = "icontains", value = "@corp")),
                mapOf("email" to "marc@example.com"),
                null,
            ),
        )
    }

    @Test
    fun `not_icontains requires no element to contain the target`() {
        val filter = listOf(filter(key = "email", operator = "not_icontains", value = "@corp"))
        assertTrue(matchTriggerPropertyFilters(filter, mapOf("email" to "marc@example.com"), null))
        assertFalse(matchTriggerPropertyFilters(filter, mapOf("email" to "a@CORP.io"), null))
    }

    @Test
    fun `regex searches the property value`() {
        val filter = listOf(filter(key = "page", operator = "regex", value = "checkout/step-\\d+"))
        assertTrue(matchTriggerPropertyFilters(filter, mapOf("page" to "/en/checkout/step-2"), null))
        assertFalse(matchTriggerPropertyFilters(filter, mapOf("page" to "/home"), null))
        assertFalse(matchTriggerPropertyFilters(filter, mapOf("page" to "checkout step x"), null))
    }

    @Test
    fun `an invalid regex never matches and its negation always does`() {
        val invalid = listOf(filter(operator = "regex", value = "(["))
        assertFalse(matchTriggerPropertyFilters(invalid, mapOf("plan" to "pro"), null))
        val invalidNot = listOf(filter(operator = "not_regex", value = "(["))
        assertTrue(matchTriggerPropertyFilters(invalidNot, mapOf("plan" to "pro"), null))
    }

    @Test
    fun `gt and lt compare numerically with js parseFloat semantics`() {
        val gt = listOf(filter(key = "amount", operator = "gt", value = 100))
        assertTrue(matchTriggerPropertyFilters(gt, mapOf("amount" to 150), null))
        assertFalse(matchTriggerPropertyFilters(gt, mapOf("amount" to 100), null))
        assertFalse(matchTriggerPropertyFilters(gt, mapOf("amount" to "free"), null))
        // parseFloat("10items") is 10, like on web
        assertFalse(matchTriggerPropertyFilters(gt, mapOf("amount" to "10items"), null))
        assertTrue(
            matchTriggerPropertyFilters(
                listOf(filter(key = "amount", operator = "gt", value = 9)),
                mapOf("amount" to "10items"),
                null,
            ),
        )

        val lt = listOf(filter(key = "amount", operator = "lt", value = "50.5"))
        assertTrue(matchTriggerPropertyFilters(lt, mapOf("amount" to 50), null))
        assertFalse(matchTriggerPropertyFilters(lt, mapOf("amount" to 50.5), null))
        assertFalse(
            matchTriggerPropertyFilters(
                listOf(filter(key = "amount", operator = "lt", value = "40")),
                mapOf("amount" to "50items"),
                null,
            ),
        )
    }

    @Test
    fun `unknown operator does not match`() {
        assertFalse(matchTriggerPropertyFilters(listOf(filter(operator = "regex_ish")), mapOf("plan" to "pro"), null))
    }

    @Test
    fun `missing filter value does not match`() {
        assertFalse(matchTriggerPropertyFilters(listOf(filter(value = null)), mapOf("plan" to "pro"), null))
        assertFalse(
            matchTriggerPropertyFilters(listOf(filter(value = null, operator = "is_not")), mapOf("plan" to "pro"), null),
        )
    }

    @Test
    fun `person type reads person properties and event type reads event properties`() {
        val filter = listOf(filter(type = "person"))
        assertTrue(matchTriggerPropertyFilters(filter, mapOf("plan" to "free"), mapOf("plan" to "pro")))
        assertFalse(matchTriggerPropertyFilters(filter, mapOf("plan" to "pro"), mapOf("plan" to "free")))
        assertFalse(matchTriggerPropertyFilters(filter, mapOf("plan" to "pro"), null))

        assertTrue(matchTriggerPropertyFilters(listOf(filter()), mapOf("plan" to "pro"), mapOf("plan" to "free")))
    }

    @Test
    fun `all filters must match`() {
        val filters =
            listOf(
                filter(key = "plan", value = "pro"),
                filter(key = "region", value = "EU"),
            )
        assertTrue(matchTriggerPropertyFilters(filters, mapOf("plan" to "pro", "region" to "EU"), null))
        assertFalse(matchTriggerPropertyFilters(filters, mapOf("plan" to "pro", "region" to "US"), null))
    }
}

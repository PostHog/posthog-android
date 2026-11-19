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
    fun `exact renders doubles the way js String does`() {
        // Each pair is a property value and what JS String() gives for it.
        listOf(
            0.000001 to "0.000001",
            0.0000001 to "1e-7",
            123.456 to "123.456",
            -0.5 to "-0.5",
            1.5e20 to "150000000000000000000",
            1e21 to "1e+21",
            9.223372036854775807E18 to "9223372036854776000",
            -0.000001 to "-0.000001",
            -1e21 to "-1e+21",
            -100.0 to "-100",
            0.0 to "0",
            -0.0 to "0",
            Double.NaN to "NaN",
            Double.POSITIVE_INFINITY to "Infinity",
            Double.NEGATIVE_INFINITY to "-Infinity",
            1.5e-7 to "1.5e-7",
            12.5 to "12.5",
            1.5 to "1.5",
        ).forEach { (property, expected) ->
            assertTrue(
                matchTriggerPropertyFilters(listOf(filter(key = "amount", value = expected)), mapOf("amount" to property), null),
                "$property should render as $expected",
            )
        }
    }

    @Test
    fun `large doubles render the shortest js digits above 2^53`() {
        // Expected values come from `String(x)` in node. JDK < 19's Double.toString (and Android's
        // ART) is not always the shortest round-trip form, which used to print 2^60 as ...698.
        // Powers of two and friends: 2^53, 2^53 + 2, 2^60, -(2^60), 2^63.
        listOf(
            9007199254740992.0 to "9007199254740992",
            9007199254740994.0 to "9007199254740994",
            1152921504606846976.0 to "1152921504606847000",
            -1152921504606846976.0 to "-1152921504606847000",
            9223372036854775808.0 to "9223372036854776000",
            1e20 to "100000000000000000000",
            1.2345678901234568e20 to "123456789012345680000",
            1e21 to "1e+21",
            0.1 to "0.1",
            1.0 / 3.0 to "0.3333333333333333",
            123.456 to "123.456",
            5.0e-7 to "5e-7",
        ).forEach { (property, expected) ->
            assertTrue(
                matchTriggerPropertyFilters(listOf(filter(key = "amount", value = expected)), mapOf("amount" to property), null),
                "$property should render as $expected",
            )
        }
    }

    @Test
    fun `object arrays match like lists`() {
        val tags = mapOf("tags" to arrayOf("premium", "vip"))

        assertTrue(matchTriggerPropertyFilters(listOf(filter(key = "tags", value = "premium")), tags, null))
        assertFalse(
            matchTriggerPropertyFilters(listOf(filter(key = "tags", value = "premium", operator = "is_not")), tags, null),
        )
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

    private fun matches(
        value: Any,
        op: String,
        target: Any,
    ) = matchTriggerPropertyFilters(
        listOf(filter(key = "value", type = "event", operator = op, value = target)),
        mapOf("value" to value),
        null,
    )

    @Test
    fun `float properties compare as their serialized decimal`() {
        assertTrue(matches(0.1, "exact", 0.1))
        assertTrue(matches(0.1f, "exact", 0.1))
        assertFalse(matches(0.1f, "is_not", 0.1))
        assertFalse(matches(0.1f, "gt", 0.1))
        assertTrue(matches(1.0E-7f, "exact", "1e-7"))
        assertTrue(matches(100f, "exact", 100))
    }

    @Test
    fun `primitive arrays match like lists`() {
        assertTrue(matches(listOf(1, 2), "exact", 2))
        assertTrue(matches(arrayOf(1, 2), "exact", 2))
        assertTrue(matches(intArrayOf(1, 2), "exact", 2))
        assertFalse(matches(intArrayOf(1, 2), "is_not", 2))
        assertTrue(matches(longArrayOf(3L), "exact", 3))
        assertTrue(matches(doubleArrayOf(0.5), "exact", 0.5))
        assertTrue(matches(floatArrayOf(0.1f), "exact", 0.1))
        assertTrue(matches(booleanArrayOf(true), "exact", "true"))
        assertFalse(matches(intArrayOf(1, 3), "exact", 2))
    }
}

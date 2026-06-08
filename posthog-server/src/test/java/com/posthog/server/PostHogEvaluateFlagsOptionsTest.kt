package com.posthog.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class PostHogEvaluateFlagsOptionsTest {
    @Test
    fun `builder creates instance with default values`() {
        val options = PostHogEvaluateFlagsOptions.builder().build()

        assertNull(options.groups)
        assertNull(options.personProperties)
        assertNull(options.groupProperties)
        assertNull(options.flagKeys)
        assertEquals(false, options.onlyEvaluateLocally)
        assertEquals(false, options.disableGeoip)
    }

    @Test
    fun `builder accumulates groups person properties and flag keys`() {
        val options =
            PostHogEvaluateFlagsOptions.builder()
                .group("organization", "org_123")
                .groups(mapOf("team" to "team_456"))
                .personProperty("plan", "premium")
                .personProperty("nullable", null)
                .personProperties(mapOf("role" to "admin"))
                .flagKeys(listOf("flag-a"))
                .flagKeys(listOf("flag-b", "flag-c"))
                .onlyEvaluateLocally(true)
                .disableGeoip(true)
                .build()

        assertEquals(mapOf("organization" to "org_123", "team" to "team_456"), options.groups)
        assertEquals(mapOf("plan" to "premium", "nullable" to null, "role" to "admin"), options.personProperties)
        assertEquals(listOf("flag-a", "flag-b", "flag-c"), options.flagKeys)
        assertEquals(true, options.onlyEvaluateLocally)
        assertEquals(true, options.disableGeoip)
    }

    @Test
    fun `groupProperty appends to existing group properties without mutating input maps`() {
        val companyProperties = mapOf<String, Any?>("industry" to "tech")
        val input = mapOf("company" to companyProperties)

        val options =
            PostHogEvaluateFlagsOptions.builder()
                .groupProperties(input)
                .groupProperty("company", "size", "large")
                .groupProperty("project", "tier", 2)
                .build()

        assertEquals(
            mapOf(
                "company" to mapOf("industry" to "tech", "size" to "large"),
                "project" to mapOf("tier" to 2),
            ),
            options.groupProperties,
        )
        assertEquals(mapOf("industry" to "tech"), companyProperties)
    }

    @Test
    fun `later values replace earlier values for matching keys`() {
        val options =
            PostHogEvaluateFlagsOptions.builder()
                .group("organization", "org_123")
                .group("organization", "org_456")
                .personProperty("plan", "free")
                .personProperty("plan", "premium")
                .groupProperty("company", "size", "small")
                .groupProperty("company", "size", "large")
                .build()

        assertEquals(mapOf("organization" to "org_456"), options.groups)
        assertEquals(mapOf("plan" to "premium"), options.personProperties)
        assertEquals(mapOf("company" to mapOf("size" to "large")), options.groupProperties)
    }
}

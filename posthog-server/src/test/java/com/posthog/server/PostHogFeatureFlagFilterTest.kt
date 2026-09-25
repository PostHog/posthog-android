package com.posthog.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class PostHogFeatureFlagFilterTest {
    @Test
    fun `builder creates a filter with no criteria`() {
        val filter = PostHogFeatureFlagFilter.builder().build()

        assertNull(filter.keys)
        assertNull(filter.evaluationRuntimes)
    }

    @Test
    fun `builder with only runtimes leaves keys unset`() {
        // The Java form of the filter a server uses to pick the flags it forwards to a browser.
        val filter = PostHogFeatureFlagFilter.builder().evaluationRuntimes("client", "all").build()

        assertNull(filter.keys)
        assertEquals(listOf("client", "all"), filter.evaluationRuntimes)
    }

    @Test
    fun `builder accumulates keys and runtimes`() {
        val filter =
            PostHogFeatureFlagFilter.builder()
                .keys(listOf("flag-a"))
                .keys("flag-b", "flag-c")
                .evaluationRuntimes(listOf("client"))
                .evaluationRuntimes("all")
                .build()

        assertEquals(listOf("flag-a", "flag-b", "flag-c"), filter.keys)
        assertEquals(listOf("client", "all"), filter.evaluationRuntimes)
    }
}

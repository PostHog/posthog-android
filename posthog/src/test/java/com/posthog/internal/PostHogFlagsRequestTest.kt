package com.posthog.internal

import com.posthog.ANON_ID
import com.posthog.API_KEY
import com.posthog.DISTINCT_ID
import com.posthog.groups
import kotlin.test.Test
import kotlin.test.assertEquals

internal class PostHogFlagsRequestTest {
    @Test
    fun `sets the flags request content`() {
        val request = PostHogFlagsRequest(API_KEY, DISTINCT_ID, anonymousId = ANON_ID, groups)

        assertEquals(API_KEY, request["api_key"])
        assertEquals(DISTINCT_ID, request["distinct_id"])
        assertEquals(ANON_ID, request["\$anon_distinct_id"])
        assertEquals(groups, request["\$groups"])
    }

    @Test
    fun `includes evaluation_environments when provided`() {
        val evaluationEnvironments = listOf("production", "web", "checkout")
        val request =
            PostHogFlagsRequest(
                API_KEY,
                DISTINCT_ID,
                evaluationEnvironments = evaluationEnvironments,
            )

        assertEquals(API_KEY, request["api_key"])
        assertEquals(DISTINCT_ID, request["distinct_id"])
        assertEquals(evaluationEnvironments, request["evaluation_environments"])
    }

    @Test
    fun `excludes evaluation_environments when null`() {
        val request = PostHogFlagsRequest(API_KEY, DISTINCT_ID)

        assertEquals(API_KEY, request["api_key"])
        assertEquals(DISTINCT_ID, request["distinct_id"])
        assertEquals(null, request["evaluation_environments"])
    }

    @Test
    fun `excludes evaluation_environments when empty`() {
        val request =
            PostHogFlagsRequest(
                API_KEY,
                DISTINCT_ID,
                evaluationEnvironments = emptyList(),
            )

        assertEquals(API_KEY, request["api_key"])
        assertEquals(DISTINCT_ID, request["distinct_id"])
        assertEquals(null, request["evaluation_environments"])
    }
}

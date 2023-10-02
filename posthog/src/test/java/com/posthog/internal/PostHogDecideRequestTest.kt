package com.posthog.internal

import com.posthog.anonId
import com.posthog.apiKey
import com.posthog.distinctId
import com.posthog.groups
import kotlin.test.Test
import kotlin.test.assertEquals

internal class PostHogDecideRequestTest {

    @Test
    fun `sets the decide request content`() {
        val request = PostHogDecideRequest(apiKey, distinctId, anonId, groups)

        assertEquals(apiKey, request["token"])
        assertEquals(distinctId, request["distinct_id"])
        assertEquals(anonId, request["\$anon_distinct_id"])
        assertEquals(groups, request["\$groups"])
    }
}

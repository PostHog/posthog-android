package com.posthog.internal

import com.posthog.ANON_ID
import com.posthog.API_KEY
import com.posthog.DISTINCT_ID
import com.posthog.groups
import kotlin.test.Test
import kotlin.test.assertEquals

internal class PostHogDecideRequestTest {
    @Test
    fun `sets the decide request content`() {
        val request = PostHogDecideRequest(API_KEY, DISTINCT_ID, disableGeoIP = true, anonymousId = ANON_ID, groups)

        assertEquals(API_KEY, request["api_key"])
        assertEquals(DISTINCT_ID, request["distinct_id"])
        assertEquals(ANON_ID, request["\$anon_distinct_id"])
        assertEquals(groups, request["\$groups"])
    }
}

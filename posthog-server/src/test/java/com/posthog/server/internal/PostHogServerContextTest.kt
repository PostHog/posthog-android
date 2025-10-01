package com.posthog.server.internal

import com.posthog.PostHogConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class PostHogServerContextTest {
    @Test
    fun `returns SDK info with lib and lib_version`() {
        val config = PostHogConfig("test-api-key")
        config.sdkName = "posthog-server"
        config.sdkVersion = "1.0.2"

        val context = PostHogServerContext(config)
        val sdkInfo = context.getSdkInfo()

        assertEquals("posthog-server", sdkInfo["\$lib"])
        assertEquals("1.0.2", sdkInfo["\$lib_version"])
    }

    @Test
    fun `returns empty static context`() {
        val config = PostHogConfig("test-api-key")
        val context = PostHogServerContext(config)

        val staticContext = context.getStaticContext()

        assertTrue(staticContext.isEmpty())
    }

    @Test
    fun `returns empty dynamic context`() {
        val config = PostHogConfig("test-api-key")
        val context = PostHogServerContext(config)

        val dynamicContext = context.getDynamicContext()

        assertTrue(dynamicContext.isEmpty())
    }
}

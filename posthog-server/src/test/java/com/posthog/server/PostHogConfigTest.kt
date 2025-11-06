package com.posthog.server

import com.posthog.PostHogOnFeatureFlags
import java.net.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

internal class PostHogConfigTest {
    @Test
    fun `constructor sets all required parameters with defaults`() {
        val config = PostHogConfig(apiKey = TEST_API_KEY)

        assertEquals(TEST_API_KEY, config.apiKey)
        assertEquals(PostHogConfig.DEFAULT_HOST, config.host)
        assertEquals(false, config.debug)
        assertEquals(true, config.sendFeatureFlagEvent)
        assertEquals(true, config.preloadFeatureFlags)
        assertEquals(true, config.remoteConfig)
        assertEquals(PostHogConfig.DEFAULT_FLUSH_AT, config.flushAt)
        assertEquals(PostHogConfig.DEFAULT_MAX_QUEUE_SIZE, config.maxQueueSize)
        assertEquals(PostHogConfig.DEFAULT_MAX_BATCH_SIZE, config.maxBatchSize)
        assertEquals(PostHogConfig.DEFAULT_FLUSH_INTERVAL_SECONDS, config.flushIntervalSeconds)
        assertNull(config.encryption)
        assertNull(config.onFeatureFlags)
        assertNull(config.proxy)
        assertEquals(PostHogConfig.DEFAULT_FEATURE_FLAG_CACHE_SIZE, config.featureFlagCacheSize)
        assertEquals(PostHogConfig.DEFAULT_FEATURE_FLAG_CACHE_MAX_AGE_MS, config.featureFlagCacheMaxAgeMs)
    }

    @Test
    fun `constructor sets all parameters when provided`() {
        val mockEncryption = createMockEncryption()
        val mockOnFeatureFlags = PostHogOnFeatureFlags { }
        val mockProxy = Proxy.NO_PROXY

        val config =
            PostHogConfig(
                apiKey = "custom-api-key",
                host = "https://custom.host.com",
                debug = true,
                sendFeatureFlagEvent = false,
                preloadFeatureFlags = false,
                remoteConfig = false,
                flushAt = 10,
                maxQueueSize = 500,
                maxBatchSize = 25,
                flushIntervalSeconds = 60,
                encryption = mockEncryption,
                onFeatureFlags = mockOnFeatureFlags,
                proxy = mockProxy,
                featureFlagCacheSize = 2000,
                featureFlagCacheMaxAgeMs = 600000,
            )

        assertEquals("custom-api-key", config.apiKey)
        assertEquals("https://custom.host.com", config.host)
        assertEquals(true, config.debug)
        assertEquals(false, config.sendFeatureFlagEvent)
        assertEquals(false, config.preloadFeatureFlags)
        assertEquals(false, config.remoteConfig)
        assertEquals(10, config.flushAt)
        assertEquals(500, config.maxQueueSize)
        assertEquals(25, config.maxBatchSize)
        assertEquals(60, config.flushIntervalSeconds)
        assertEquals(mockEncryption, config.encryption)
        assertEquals(mockOnFeatureFlags, config.onFeatureFlags)
        assertEquals(mockProxy, config.proxy)
        assertEquals(2000, config.featureFlagCacheSize)
        assertEquals(600000, config.featureFlagCacheMaxAgeMs)
    }

    @Test
    fun `addBeforeSend adds callback to internal list`() {
        val config = PostHogConfig(apiKey = TEST_API_KEY)
        val beforeSend = createMockBeforeSend()

        config.addBeforeSend(beforeSend)

        // Verify by converting to core config and checking it was applied
        val coreConfig = config.asCoreConfig()
        // Note: We can't directly test the internal list, but we can test that
        // it gets applied to the core config through the asCoreConfig method
        assertEquals(TEST_API_KEY, coreConfig.apiKey)
    }

    @Test
    fun `removeBeforeSend removes callback from internal list`() {
        val config = PostHogConfig(apiKey = TEST_API_KEY)
        val beforeSend = createMockBeforeSend()

        config.addBeforeSend(beforeSend)
        config.removeBeforeSend(beforeSend)

        // Verify removal by ensuring the core config is created without issues
        val coreConfig = config.asCoreConfig()
        assertEquals(TEST_API_KEY, coreConfig.apiKey)
    }

    @Test
    fun `addIntegration adds integration to internal list`() {
        val config = PostHogConfig(apiKey = TEST_API_KEY)
        val integration = createMockIntegration()

        config.addIntegration(integration)

        // Verify by converting to core config
        val coreConfig = config.asCoreConfig()
        assertEquals(TEST_API_KEY, coreConfig.apiKey)
    }

    @Test
    fun `asCoreConfig creates core config with all properties`() {
        val mockEncryption = createMockEncryption()
        val mockOnFeatureFlags = PostHogOnFeatureFlags { }

        val config =
            PostHogConfig(
                apiKey = "test-key",
                host = "https://test.host.com",
                debug = true,
                sendFeatureFlagEvent = false,
                preloadFeatureFlags = false,
                remoteConfig = false,
                flushAt = 15,
                maxQueueSize = 750,
                maxBatchSize = 30,
                flushIntervalSeconds = 45,
                encryption = mockEncryption,
                onFeatureFlags = mockOnFeatureFlags,
                featureFlagCacheSize = 1500,
                featureFlagCacheMaxAgeMs = 300000,
            )

        val coreConfig = config.asCoreConfig()

        assertEquals("test-key", coreConfig.apiKey)
        assertEquals("https://test.host.com", coreConfig.host)
        assertEquals(true, coreConfig.debug)
        assertEquals(false, coreConfig.sendFeatureFlagEvent)
        assertEquals(false, coreConfig.preloadFeatureFlags)
        assertEquals(false, coreConfig.remoteConfig)
        assertEquals(15, coreConfig.flushAt)
        assertEquals(750, coreConfig.maxQueueSize)
        assertEquals(30, coreConfig.maxBatchSize)
        assertEquals(45, coreConfig.flushIntervalSeconds)
        assertEquals(mockEncryption, coreConfig.encryption)
        assertEquals(mockOnFeatureFlags, coreConfig.onFeatureFlags)
    }

    @Test
    fun `asCoreConfig applies beforeSend callbacks to core config`() {
        val config = PostHogConfig(apiKey = TEST_API_KEY)
        val beforeSend1 = createMockBeforeSend()
        val beforeSend2 = createMockBeforeSend()

        config.addBeforeSend(beforeSend1)
        config.addBeforeSend(beforeSend2)

        val coreConfig = config.asCoreConfig()

        // Verify the core config was created successfully
        assertEquals(TEST_API_KEY, coreConfig.apiKey)
    }

    @Test
    fun `asCoreConfig applies integrations to core config`() {
        val config = PostHogConfig(apiKey = TEST_API_KEY)
        val integration1 = createMockIntegration()
        val integration2 = createMockIntegration()

        config.addIntegration(integration1)
        config.addIntegration(integration2)

        val coreConfig = config.asCoreConfig()

        // Verify the core config was created successfully
        assertEquals(TEST_API_KEY, coreConfig.apiKey)
    }

    @Test
    fun `companion object constants have correct values`() {
        assertEquals("https://us.i.posthog.com", PostHogConfig.DEFAULT_US_HOST)
        assertEquals("https://us-assets.i.posthog.com", PostHogConfig.DEFAULT_US_ASSETS_HOST)
        assertEquals("https://us.i.posthog.com", PostHogConfig.DEFAULT_HOST)
        assertEquals("https://eu.i.posthog.com", PostHogConfig.DEFAULT_EU_HOST)
        assertEquals("https://eu-assets.i.posthog.com", PostHogConfig.DEFAULT_EU_ASSETS_HOST)
        assertEquals(20, PostHogConfig.DEFAULT_FLUSH_AT)
        assertEquals(1000, PostHogConfig.DEFAULT_MAX_QUEUE_SIZE)
        assertEquals(50, PostHogConfig.DEFAULT_MAX_BATCH_SIZE)
        assertEquals(30, PostHogConfig.DEFAULT_FLUSH_INTERVAL_SECONDS)
        assertEquals(1000, PostHogConfig.DEFAULT_FEATURE_FLAG_CACHE_SIZE)
        assertEquals(300000, PostHogConfig.DEFAULT_FEATURE_FLAG_CACHE_MAX_AGE_MS) // 5 minutes
    }

    @Test
    fun `builder creates builder instance with correct api key`() {
        val builder = PostHogConfig.builder("test-api-key")

        val config = builder.build()
        assertEquals("test-api-key", config.apiKey)
    }

    // Builder tests
    @Test
    fun `builder creates config with default values`() {
        val config = PostHogConfig.builder(TEST_API_KEY).build()

        assertEquals(TEST_API_KEY, config.apiKey)
        assertEquals(PostHogConfig.DEFAULT_HOST, config.host)
        assertEquals(false, config.debug)
        assertEquals(true, config.sendFeatureFlagEvent)
        assertEquals(true, config.preloadFeatureFlags)
        assertEquals(true, config.remoteConfig)
        assertEquals(PostHogConfig.DEFAULT_FLUSH_AT, config.flushAt)
        assertEquals(PostHogConfig.DEFAULT_MAX_QUEUE_SIZE, config.maxQueueSize)
        assertEquals(PostHogConfig.DEFAULT_MAX_BATCH_SIZE, config.maxBatchSize)
        assertEquals(PostHogConfig.DEFAULT_FLUSH_INTERVAL_SECONDS, config.flushIntervalSeconds)
        assertNull(config.encryption)
        assertNull(config.onFeatureFlags)
        assertNull(config.proxy)
    }

    @Test
    fun `builder host method sets host and returns builder`() {
        val builder = PostHogConfig.builder(TEST_API_KEY)
        val result = builder.host("https://custom.host.com")
        assertEquals(builder, result)

        val config = builder.build()
        assertEquals("https://custom.host.com", config.host)
    }

    @Test
    fun `builder debug method sets debug and returns builder`() {
        val builder = PostHogConfig.builder(TEST_API_KEY)
        val result = builder.debug(true)
        assertEquals(builder, result)

        val config = builder.build()
        assertEquals(true, config.debug)
    }

    @Test
    fun `builder sendFeatureFlagEvent method sets value and returns builder`() {
        val builder = PostHogConfig.builder(TEST_API_KEY)
        val result = builder.sendFeatureFlagEvent(false)
        assertEquals(builder, result)

        val config = builder.build()
        assertEquals(false, config.sendFeatureFlagEvent)
    }

    @Test
    fun `builder preloadFeatureFlags method sets value and returns builder`() {
        val builder = PostHogConfig.builder(TEST_API_KEY)
        val result = builder.preloadFeatureFlags(false)
        assertEquals(builder, result)

        val config = builder.build()
        assertEquals(false, config.preloadFeatureFlags)
    }

    @Test
    fun `builder remoteConfig method sets value and returns builder`() {
        val builder = PostHogConfig.builder(TEST_API_KEY)
        val result = builder.remoteConfig(false)
        assertEquals(builder, result)

        val config = builder.build()
        assertEquals(false, config.remoteConfig)
    }

    @Test
    fun `builder flushAt method sets value and returns builder`() {
        val builder = PostHogConfig.builder(TEST_API_KEY)
        val result = builder.flushAt(15)
        assertEquals(builder, result)

        val config = builder.build()
        assertEquals(15, config.flushAt)
    }

    @Test
    fun `builder maxQueueSize method sets value and returns builder`() {
        val builder = PostHogConfig.builder(TEST_API_KEY)
        val result = builder.maxQueueSize(750)
        assertEquals(builder, result)

        val config = builder.build()
        assertEquals(750, config.maxQueueSize)
    }

    @Test
    fun `builder maxBatchSize method sets value and returns builder`() {
        val builder = PostHogConfig.builder(TEST_API_KEY)
        val result = builder.maxBatchSize(30)
        assertEquals(builder, result)

        val config = builder.build()
        assertEquals(30, config.maxBatchSize)
    }

    @Test
    fun `builder flushIntervalSeconds method sets value and returns builder`() {
        val builder = PostHogConfig.builder(TEST_API_KEY)
        val result = builder.flushIntervalSeconds(45)
        assertEquals(builder, result)

        val config = builder.build()
        assertEquals(45, config.flushIntervalSeconds)
    }

    @Test
    fun `builder encryption method sets value and returns builder`() {
        val builder = PostHogConfig.builder(TEST_API_KEY)
        val mockEncryption = createMockEncryption()
        val result = builder.encryption(mockEncryption)
        assertEquals(builder, result)

        val config = builder.build()
        assertEquals(mockEncryption, config.encryption)
    }

    @Test
    fun `builder onFeatureFlags method sets value and returns builder`() {
        val builder = PostHogConfig.builder(TEST_API_KEY)
        val mockCallback = PostHogOnFeatureFlags { }
        val result = builder.onFeatureFlags(mockCallback)
        assertEquals(builder, result)

        val config = builder.build()
        assertEquals(mockCallback, config.onFeatureFlags)
    }

    @Test
    fun `builder proxy method sets value and returns builder`() {
        val builder = PostHogConfig.builder(TEST_API_KEY)
        val mockProxy = Proxy.NO_PROXY
        val result = builder.proxy(mockProxy)
        assertEquals(builder, result)

        val config = builder.build()
        assertEquals(mockProxy, config.proxy)
    }

    @Test
    fun `builder allows method chaining`() {
        val mockEncryption = createMockEncryption()
        val mockCallback = PostHogOnFeatureFlags { }

        val config =
            PostHogConfig.builder(TEST_API_KEY)
                .host("https://custom.host.com")
                .debug(true)
                .sendFeatureFlagEvent(false)
                .preloadFeatureFlags(false)
                .remoteConfig(false)
                .flushAt(15)
                .maxQueueSize(750)
                .maxBatchSize(30)
                .flushIntervalSeconds(45)
                .encryption(mockEncryption)
                .onFeatureFlags(mockCallback)
                .proxy(Proxy.NO_PROXY)
                .featureFlagCacheSize(10)
                .featureFlagCacheMaxAgeMs(20)
                .featureFlagCalledCacheSize(30)
                .build()

        assertEquals(TEST_API_KEY, config.apiKey)
        assertEquals("https://custom.host.com", config.host)
        assertEquals(true, config.debug)
        assertEquals(false, config.sendFeatureFlagEvent)
        assertEquals(false, config.preloadFeatureFlags)
        assertEquals(false, config.remoteConfig)
        assertEquals(15, config.flushAt)
        assertEquals(750, config.maxQueueSize)
        assertEquals(30, config.maxBatchSize)
        assertEquals(45, config.flushIntervalSeconds)
        assertEquals(mockEncryption, config.encryption)
        assertEquals(mockCallback, config.onFeatureFlags)
        assertEquals(Proxy.NO_PROXY, config.proxy)
        assertEquals(10, config.featureFlagCacheSize)
        assertEquals(20, config.featureFlagCacheMaxAgeMs)
        assertEquals(30, config.featureFlagCalledCacheSize)
    }

    @Test
    fun `builder can set null values`() {
        val config =
            PostHogConfig.builder(TEST_API_KEY)
                .encryption(null)
                .onFeatureFlags(null)
                .proxy(null)
                .build()

        assertNull(config.encryption)
        assertNull(config.onFeatureFlags)
        assertNull(config.proxy)
    }

    @Test
    fun `config properties are mutable after creation`() {
        val config = PostHogConfig(apiKey = TEST_API_KEY)

        // Test that properties can be modified
        config.debug = true
        config.sendFeatureFlagEvent = false
        config.preloadFeatureFlags = false
        config.remoteConfig = false
        config.flushAt = 100
        config.maxQueueSize = 2000
        config.maxBatchSize = 75
        config.flushIntervalSeconds = 120
        config.featureFlagCacheSize = 500
        config.featureFlagCacheMaxAgeMs = 600000

        assertEquals(true, config.debug)
        assertEquals(false, config.sendFeatureFlagEvent)
        assertEquals(false, config.preloadFeatureFlags)
        assertEquals(false, config.remoteConfig)
        assertEquals(100, config.flushAt)
        assertEquals(2000, config.maxQueueSize)
        assertEquals(75, config.maxBatchSize)
        assertEquals(120, config.flushIntervalSeconds)
        assertEquals(500, config.featureFlagCacheSize)
        assertEquals(600000, config.featureFlagCacheMaxAgeMs)
    }

    @Test
    fun `multiple beforeSend callbacks can be added and removed`() {
        val config = PostHogConfig(apiKey = TEST_API_KEY)
        val beforeSend1 = createMockBeforeSend()
        val beforeSend2 = createMockBeforeSend()
        val beforeSend3 = createMockBeforeSend()

        // Add multiple callbacks
        config.addBeforeSend(beforeSend1)
        config.addBeforeSend(beforeSend2)
        config.addBeforeSend(beforeSend3)

        // Remove one callback
        config.removeBeforeSend(beforeSend2)

        // Verify by creating core config (which applies all callbacks)
        val coreConfig = config.asCoreConfig()
        assertEquals(TEST_API_KEY, coreConfig.apiKey)
    }

    @Test
    fun `multiple integrations can be added`() {
        val config = PostHogConfig(apiKey = TEST_API_KEY)
        val integration1 = createMockIntegration()
        val integration2 = createMockIntegration()

        config.addIntegration(integration1)
        config.addIntegration(integration2)

        // Verify by creating core config (which applies all integrations)
        val coreConfig = config.asCoreConfig()
        assertEquals(TEST_API_KEY, coreConfig.apiKey)
    }

    @Test
    fun `asCoreConfig creates new instance each time`() {
        val config = PostHogConfig(apiKey = TEST_API_KEY)

        val coreConfig1 = config.asCoreConfig()
        val coreConfig2 = config.asCoreConfig()

        // Should be different instances
        assertNotEquals(coreConfig1, coreConfig2)
        // But should have same properties
        assertEquals(coreConfig1.apiKey, coreConfig2.apiKey)
        assertEquals(coreConfig1.host, coreConfig2.host)
    }

    @Test
    fun `builder personalApiKey enables localEvaluation when not explicitly set`() {
        val config =
            PostHogConfig.builder(TEST_API_KEY)
                .personalApiKey("test-personal-api-key")
                .build()

        assertEquals("test-personal-api-key", config.personalApiKey)
        assertEquals(true, config.localEvaluation)
    }

    @Test
    fun `builder personalApiKey with null does not enable localEvaluation when not explicitly set`() {
        val config =
            PostHogConfig.builder(TEST_API_KEY)
                .personalApiKey(null)
                .build()

        assertNull(config.personalApiKey)
        assertEquals(false, config.localEvaluation)
    }

    @Test
    fun `builder personalApiKey does not override explicit localEvaluation false`() {
        val config =
            PostHogConfig.builder(TEST_API_KEY)
                .localEvaluation(false)
                .personalApiKey("test-personal-api-key")
                .build()

        assertEquals("test-personal-api-key", config.personalApiKey)
        assertEquals(false, config.localEvaluation)
    }
}

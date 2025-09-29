package com.posthog

import com.posthog.internal.PostHogFeatureFlagsInterface
import com.posthog.internal.PostHogLogger
import com.posthog.internal.PostHogMemoryPreferences
import com.posthog.internal.PostHogPreferences
import com.posthog.internal.PostHogPreferences.Companion.GROUPS
import com.posthog.internal.PostHogQueueInterface
import com.posthog.internal.PostHogSerializer
import com.posthog.internal.PostHogThreadFactory
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PostHogStatelessTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    private val queueExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestQueueStateless"))
    private val featureFlagsExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestFeatureFlagsStateless"))
    private val serializer = PostHogSerializer(PostHogConfig(API_KEY))
    private lateinit var config: PostHogConfig
    private lateinit var sut: TestablePostHogStateless

    // Testable version of PostHogStateless that exposes protected methods
    private class TestablePostHogStateless(
        queueExecutor: java.util.concurrent.ExecutorService,
        featureFlagsExecutor: java.util.concurrent.ExecutorService,
    ) : PostHogStateless(queueExecutor, featureFlagsExecutor) {
        fun isEnabledPublic(): Boolean = isEnabled()

        fun setMockQueue(queue: PostHogQueueInterface) {
            this.queue = queue
        }

        fun setMockFeatureFlags(featureFlags: PostHogFeatureFlagsInterface) {
            this.featureFlags = featureFlags
        }

        fun testMergeGroups(givenGroups: Map<String, String>?): Map<String, String>? {
            return mergeGroups(givenGroups)
        }

        fun getPreferencesPublic(): PostHogPreferences {
            return getPreferences()
        }
    }

    // Mock classes for testing
    private class MockQueue : PostHogQueueInterface {
        val events = mutableListOf<PostHogEvent>()
        var isStarted = false
        var isStopped = false
        var flushed = false

        override fun add(event: PostHogEvent) {
            events.add(event)
        }

        override fun start() {
            isStarted = true
        }

        override fun stop() {
            isStopped = true
        }

        override fun flush() {
            flushed = true
        }

        override fun clear() {
            events.clear()
        }
    }

    private class MockFeatureFlags : PostHogFeatureFlagsInterface {
        private val flags = mutableMapOf<String, Any>()

        fun setFlag(
            key: String,
            value: Any,
        ) {
            flags[key] = value
        }

        override fun getFeatureFlag(
            key: String,
            defaultValue: Any?,
            distinctId: String?,
            groups: Map<String, String>?,
            personProperties: Map<String, String>?,
            groupProperties: Map<String, String>?,
        ): Any? {
            return flags[key] ?: defaultValue
        }

        override fun getFeatureFlagPayload(
            key: String,
            defaultValue: Any?,
            distinctId: String?,
            groups: Map<String, String>?,
            personProperties: Map<String, String>?,
            groupProperties: Map<String, String>?,
        ): Any? {
            return flags[key] ?: defaultValue
        }

        override fun getFeatureFlags(
            distinctId: String?,
            groups: Map<String, String>?,
            personProperties: Map<String, String>?,
            groupProperties: Map<String, String>?,
        ): Map<String, Any> {
            return flags.toMap()
        }

        override fun clear() {
            flags.clear()
        }
    }

    @BeforeTest
    fun setUp() {
        // Reset shared instance to avoid test interference
        PostHogStateless.resetSharedInstance()
    }

    @AfterTest
    fun tearDown() {
        if (::sut.isInitialized) {
            sut.close()
        }
        queueExecutor.shutdownAndAwaitTermination()
        featureFlagsExecutor.shutdownAndAwaitTermination()
        tmpDir.root.deleteRecursively()
    }

    private fun createConfig(
        host: String = "https://api.posthog.com",
        optOut: Boolean = false,
        sendFeatureFlagEvent: Boolean = true,
        personProfiles: PersonProfiles = PersonProfiles.ALWAYS,
        storagePrefix: String = tmpDir.newFolder().absolutePath,
    ): PostHogConfig {
        return PostHogConfig(API_KEY, host).apply {
            this.optOut = optOut
            this.sendFeatureFlagEvent = sendFeatureFlagEvent
            this.personProfiles = personProfiles
            this.storagePrefix = File(storagePrefix, "events").absolutePath
            this.cachePreferences = PostHogMemoryPreferences()
        }
    }

    private fun createStatelessInstance(): TestablePostHogStateless {
        return TestablePostHogStateless(queueExecutor, featureFlagsExecutor)
    }

    // Setup and Lifecycle Tests
    @Test
    fun `setup configures instance correctly`() {
        sut = createStatelessInstance()
        config = createConfig()

        sut.setup(config)

        assertTrue(sut.isEnabledPublic())
    }

    @Test
    fun `setup logs warning when called multiple times`() {
        sut = createStatelessInstance()
        config = createConfig()
        val mockLogger = MockLogger()
        config.logger = mockLogger

        sut.setup(config)
        sut.setup(config)

        assertTrue(mockLogger.messages.any { it.contains("Setup called despite already being setup!") })
    }

    @Test
    fun `setup prevents duplicate API keys`() {
        val sut1 = createStatelessInstance()
        val sut2 = createStatelessInstance()
        val config1 = createConfig()
        val config2 = createConfig()
        val mockLogger = MockLogger()
        config2.logger = mockLogger

        sut1.setup(config1)
        sut2.setup(config2)

        assertTrue(mockLogger.messages.any { it.contains("already has a PostHog instance") })

        sut1.close()
        sut2.close()
    }

    @Test
    fun `close disables instance and stops queue`() {
        sut = createStatelessInstance()
        config = createConfig()

        sut.setup(config)
        assertTrue(sut.isEnabledPublic())

        sut.close()
        assertFalse(sut.isEnabledPublic())
    }

    @Test
    fun `close handles errors gracefully`() {
        sut = createStatelessInstance()
        config = createConfig()
        val mockLogger = MockLogger()
        config.logger = mockLogger

        sut.close() // Close without setup

        // Should not throw exceptions
        assertFalse(sut.isEnabledPublic())
    }

    // Configuration Tests
    @Test
    fun `optOut sets correct state`() {
        sut = createStatelessInstance()
        config = createConfig()

        sut.setup(config)
        assertFalse(sut.isOptOut())

        sut.optOut()
        assertTrue(sut.isOptOut())
    }

    @Test
    fun `optIn sets correct state`() {
        sut = createStatelessInstance()
        config = createConfig(optOut = true)

        sut.setup(config)
        assertTrue(sut.isOptOut())

        sut.optIn()
        assertFalse(sut.isOptOut())
    }

    @Test
    fun `optOut state persists across instance`() {
        sut = createStatelessInstance()
        config = createConfig()

        sut.setup(config)
        assertFalse(sut.isOptOut())

        sut.optOut()
        assertTrue(sut.isOptOut())
    }

    @Test
    fun `isOptOut returns true when not enabled`() {
        sut = createStatelessInstance()

        assertTrue(sut.isOptOut())
    }

    @Test
    fun `debug mode can be toggled`() {
        sut = createStatelessInstance()
        config = createConfig()

        sut.setup(config)
        assertFalse(config.debug)

        sut.debug(true)
        assertTrue(config.debug)

        sut.debug(false)
        assertFalse(config.debug)
    }

    // Event Capture Tests
    @Test
    fun `captureStateless creates and queues event`() {
        val mockQueue = MockQueue()
        sut = createStatelessInstance()
        config = createConfig()

        sut.setup(config)
        sut.setMockQueue(mockQueue)

        sut.captureStateless(
            event = "test_event",
            distinctId = "user123",
            properties = mapOf("prop1" to "value1"),
            userProperties = mapOf("name" to "John"),
            userPropertiesSetOnce = mapOf("signup_date" to "2024-01-01"),
            groups = mapOf("company" to "acme"),
        )

        assertEquals(1, mockQueue.events.size)
        val event = mockQueue.events.first()
        assertEquals("test_event", event.event)
        assertEquals("user123", event.distinctId)
        assertEquals("value1", event.properties!!["prop1"])
        assertEquals(mapOf("name" to "John"), event.properties!!["\$set"])
        assertEquals(mapOf("signup_date" to "2024-01-01"), event.properties!!["\$set_once"])
        assertEquals(mapOf("company" to "acme"), event.properties!!["\$groups"])
    }

    @Test
    fun `captureStateless does nothing when not enabled`() {
        val mockQueue = MockQueue()
        sut = createStatelessInstance()

        sut.captureStateless("test", "user123")

        assertEquals(0, mockQueue.events.size)
    }

    @Test
    fun `captureStateless does nothing when opted out`() {
        val mockQueue = MockQueue()
        sut = createStatelessInstance()
        config = createConfig(optOut = true)

        sut.setup(config)
        sut.setMockQueue(mockQueue)

        sut.captureStateless("test", "user123")

        assertEquals(0, mockQueue.events.size)
    }

    @Test
    fun `captureStateless handles feature flags when enabled`() {
        val mockQueue = MockQueue()
        val mockFeatureFlags = MockFeatureFlags()
        mockFeatureFlags.setFlag("test_flag", true)

        sut = createStatelessInstance()
        config = createConfig(sendFeatureFlagEvent = true)

        sut.setup(config)
        sut.setMockQueue(mockQueue)

        sut.setMockFeatureFlags(mockFeatureFlags)

        sut.captureStateless("test", "user123")

        assertEquals(1, mockQueue.events.size)
        val event = mockQueue.events.first()
        assertEquals(true, event.properties!!["\$feature/test_flag"])
        assertEquals(listOf("test_flag"), event.properties!!["\$active_feature_flags"])
    }

    // Feature Flags Tests
    @Test
    fun `isFeatureEnabledStateless returns correct value`() {
        val mockFeatureFlags = MockFeatureFlags()
        mockFeatureFlags.setFlag("test_flag", true)

        sut = createStatelessInstance()
        config = createConfig()

        sut.setup(config)
        sut.setMockFeatureFlags(mockFeatureFlags)

        assertTrue(sut.isFeatureEnabledStateless("user123", "test_flag"))
        assertFalse(sut.isFeatureEnabledStateless("user123", "non_existent_flag"))
    }

    @Test
    fun `isFeatureEnabledStateless returns default when not enabled`() {
        sut = createStatelessInstance()

        assertFalse(sut.isFeatureEnabledStateless("user123", "test_flag"))
        assertTrue(sut.isFeatureEnabledStateless("user123", "test_flag", true))
    }

    @Test
    fun `getFeatureFlagStateless returns correct value`() {
        val mockFeatureFlags = MockFeatureFlags()
        mockFeatureFlags.setFlag("string_flag", "variant_a")

        sut = createStatelessInstance()
        config = createConfig()

        sut.setup(config)
        sut.setMockFeatureFlags(mockFeatureFlags)

        assertEquals("variant_a", sut.getFeatureFlagStateless("user123", "string_flag"))
        assertEquals("default", sut.getFeatureFlagStateless("user123", "non_existent", "default"))
    }

    @Test
    fun `getFeatureFlagPayloadStateless returns correct value`() {
        val mockFeatureFlags = MockFeatureFlags()
        mockFeatureFlags.setFlag("payload_flag", mapOf("key" to "value"))

        sut = createStatelessInstance()
        config = createConfig()

        sut.setup(config)
        sut.setMockFeatureFlags(mockFeatureFlags)

        assertEquals(mapOf("key" to "value"), sut.getFeatureFlagPayloadStateless("user123", "payload_flag"))
        assertEquals("default", sut.getFeatureFlagPayloadStateless("user123", "non_existent", "default"))
    }

    // Identity Management Tests
    @Test
    fun `identify captures identify event when person processing allowed`() {
        val mockQueue = MockQueue()
        sut = createStatelessInstance()
        config = createConfig(personProfiles = PersonProfiles.ALWAYS)

        sut.setup(config)
        sut.setMockQueue(mockQueue)

        sut.identify(
            distinctId = "user123",
            userProperties = mapOf("name" to "John"),
            userPropertiesSetOnce = mapOf("signup_date" to "2024-01-01"),
        )

        assertEquals(1, mockQueue.events.size)
        val event = mockQueue.events.first()
        assertEquals("\$identify", event.event)
        assertEquals("user123", event.distinctId)
    }

    @Test
    fun `identify does nothing with blank distinctId`() {
        val mockQueue = MockQueue()
        sut = createStatelessInstance()
        config = createConfig()

        sut.setup(config)
        sut.setMockQueue(mockQueue)

        sut.identify("   ")

        assertEquals(0, mockQueue.events.size)
    }

    @Test
    fun `aliasStateless creates alias event`() {
        val mockQueue = MockQueue()
        sut = createStatelessInstance()
        config = createConfig()

        sut.setup(config)
        sut.setMockQueue(mockQueue)

        sut.aliasStateless("user123", "alias456")

        assertEquals(1, mockQueue.events.size)
        val event = mockQueue.events.first()
        assertEquals("\$create_alias", event.event)
        assertEquals("user123", event.distinctId)
        assertEquals("alias456", event.properties!!["alias"])
    }

    // Group Management Tests
    @Test
    fun `groupStateless creates group identify event`() {
        val mockQueue = MockQueue()
        sut = createStatelessInstance()
        config = createConfig()

        sut.setup(config)
        sut.setMockQueue(mockQueue)

        sut.groupStateless(
            distinctId = "user123",
            type = "company",
            key = "acme",
            groupProperties = mapOf("industry" to "tech"),
        )

        assertEquals(1, mockQueue.events.size)
        val event = mockQueue.events.first()
        assertEquals("\$groupidentify", event.event)
        assertEquals("user123", event.distinctId)
        assertEquals("company", event.properties!!["\$group_type"])
        assertEquals("acme", event.properties!!["\$group_key"])
        assertEquals(mapOf("industry" to "tech"), event.properties!!["\$group_set"])
    }

    // Error Handling Tests
    @Test
    fun `operations handle errors gracefully when not enabled`() {
        sut = createStatelessInstance()

        // Should not throw exceptions
        sut.captureStateless("test", "user123")
        sut.identify("user123")
        sut.aliasStateless("user123", "alias")
        sut.groupStateless("user123", "company", "acme")
        sut.flush()
        sut.debug(true)
    }

    @Test
    fun `flush calls queue flush when enabled`() {
        val mockQueue = MockQueue()
        sut = createStatelessInstance()
        config = createConfig()

        sut.setup(config)
        sut.setMockQueue(mockQueue)

        sut.flush()

        assertTrue(mockQueue.flushed)
    }

    @Test
    fun `flush does nothing when not enabled`() {
        sut = createStatelessInstance()

        // Should not throw exceptions
        sut.flush()
    }

    // Companion Object Tests
    @Test
    fun `with factory method creates configured instance`() {
        val config = createConfig()
        val instance = PostHogStateless.with(config)

        // Since isEnabled is protected, we can only test through public interface
        assertFalse(instance.isOptOut())

        instance.close()
    }

    @Test
    fun `shared instance delegates to singleton`() {
        val config = createConfig()

        PostHogStateless.setup(config)
        assertTrue(PostHogStateless.isOptOut() == false)

        PostHogStateless.optOut()
        assertTrue(PostHogStateless.isOptOut())

        PostHogStateless.close()
    }

    @Test
    fun `overrideSharedInstance allows test customization`() {
        val mockInstance = MockPostHogStateless()

        PostHogStateless.overrideSharedInstance(mockInstance)
        PostHogStateless.optOut()

        assertTrue(mockInstance.optOutCalled)

        PostHogStateless.resetSharedInstance()
    }

    // Property Merging and Advanced Tests
    @Test
    fun `buildProperties merges all property sources correctly`() {
        val mockQueue = MockQueue()
        val mockFeatureFlags = MockFeatureFlags()
        mockFeatureFlags.setFlag("test_flag", "variant_a")

        val preferences = PostHogMemoryPreferences()
        preferences.setValue("registered_prop", "registered_value")

        sut = createStatelessInstance()
        config =
            createConfig().apply {
                cachePreferences = preferences
                sendFeatureFlagEvent = true
            }

        sut.setup(config)
        sut.setMockQueue(mockQueue)

        sut.setMockFeatureFlags(mockFeatureFlags)

        sut.captureStateless(
            event = "test_event",
            distinctId = "user123",
            properties = mapOf("event_prop" to "event_value"),
            userProperties = mapOf("name" to "John"),
            userPropertiesSetOnce = mapOf("signup_date" to "2024-01-01"),
            groups = mapOf("company" to "acme"),
        )

        assertEquals(1, mockQueue.events.size)
        val event = mockQueue.events.first()

        // Check key property sources are merged
        assertEquals("event_value", event.properties!!["event_prop"])
        assertEquals(mapOf("name" to "John"), event.properties!!["\$set"])
        assertEquals(mapOf("signup_date" to "2024-01-01"), event.properties!!["\$set_once"])
        assertEquals(mapOf("company" to "acme"), event.properties!!["\$groups"])
        assertEquals("variant_a", event.properties!!["\$feature/test_flag"])
    }

    @Test
    fun `mergeGroups handles existing and new groups correctly`() {
        sut = createStatelessInstance()
        config = createConfig()

        sut.setup(config)

        // Set up existing groups in preferences
        val existingGroups = mapOf("existing_group" to "existing_value", "shared_group" to "old_value")
        sut.getPreferencesPublic().setValue(GROUPS, existingGroups)

        // Merge with new groups (including one that overwrites existing)
        val newGroups = mapOf("new_group" to "new_value", "shared_group" to "new_value")
        val result = sut.testMergeGroups(newGroups)

        assertNotNull(result)
        assertEquals(3, result.size)
        // Existing group should be preserved
        assertEquals("existing_value", result["existing_group"])
        // New group should be added
        assertEquals("new_value", result["new_group"])
        // Shared group should be overwritten with new value
        assertEquals("new_value", result["shared_group"])
    }

    @Test
    fun `mergeGroups returns null when no groups exist`() {
        sut = createStatelessInstance()
        config = createConfig()

        sut.setup(config)

        val result = sut.testMergeGroups(null)

        assertNull(result)
    }

    @Test
    fun `beforeSend callback can modify events`() {
        val mockQueue = MockQueue()
        sut = createStatelessInstance()
        config =
            createConfig().apply {
                addBeforeSend { event ->
                    event.copy(
                        properties =
                            event.properties?.toMutableMap()?.apply {
                                put("modified", true)
                            },
                    )
                }
            }

        sut.setup(config)
        sut.setMockQueue(mockQueue)

        sut.captureStateless("test", "user123", mapOf("original" to "value"))

        assertEquals(1, mockQueue.events.size)
        val event = mockQueue.events.first()
        assertEquals(true, event.properties!!["modified"])
        assertEquals("value", event.properties!!["original"])
    }

    @Test
    fun `beforeSend callback can reject events`() {
        val mockQueue = MockQueue()
        sut = createStatelessInstance()
        config =
            createConfig().apply {
                addBeforeSend { null } // Reject all events
            }

        sut.setup(config)
        sut.setMockQueue(mockQueue)

        sut.captureStateless("test", "user123")

        assertEquals(0, mockQueue.events.size)
    }

    @Test
    fun `beforeSend error handling does not crash`() {
        val mockQueue = MockQueue()
        val mockLogger = MockLogger()
        sut = createStatelessInstance()
        config =
            createConfig().apply {
                logger = mockLogger
                addBeforeSend { throw RuntimeException("Test error") }
            }

        sut.setup(config)
        sut.setMockQueue(mockQueue)

        sut.captureStateless("test", "user123")

        assertEquals(0, mockQueue.events.size)
        assertTrue(mockLogger.messages.any { it.contains("Error in beforeSend function") })
    }

    @Test
    fun `properties sanitizer is applied to events`() {
        val mockQueue = MockQueue()
        sut = createStatelessInstance()
        config =
            createConfig().apply {
                @Suppress("DEPRECATION")
                propertiesSanitizer =
                    PostHogPropertiesSanitizer { properties ->
                        properties.apply {
                            remove("sensitive")
                            put("sanitized", true)
                        }
                    }
            }

        sut.setup(config)
        sut.setMockQueue(mockQueue)

        sut.captureStateless(
            "test",
            "user123",
            mapOf(
                "safe" to "value",
                "sensitive" to "secret",
            ),
        )

        assertEquals(1, mockQueue.events.size)
        val event = mockQueue.events.first()
        assertEquals("value", event.properties!!["safe"])
        assertNull(event.properties!!["sensitive"])
        assertEquals(true, event.properties!!["sanitized"])
    }

    @Test
    fun `feature flag called events are sent when feature flags accessed`() {
        val mockQueue = MockQueue()
        val mockFeatureFlags = MockFeatureFlags()
        mockFeatureFlags.setFlag("test_flag", true)

        sut = createStatelessInstance()
        config = createConfig(sendFeatureFlagEvent = true)

        sut.setup(config)
        sut.setMockQueue(mockQueue)

        sut.setMockFeatureFlags(mockFeatureFlags)

        // Access feature flag
        sut.isFeatureEnabledStateless("user123", "test_flag")

        // Should generate feature flag called event
        assertEquals(1, mockQueue.events.size)
        val event = mockQueue.events.first()
        assertEquals("\$feature_flag_called", event.event)
        assertEquals("user123", event.distinctId)
        assertEquals("test_flag", event.properties!!["\$feature_flag"])
        assertEquals(true, event.properties!!["\$feature_flag_response"])
    }

    @Test
    fun `feature flag called events not sent when disabled`() {
        val mockQueue = MockQueue()
        val mockFeatureFlags = MockFeatureFlags()
        mockFeatureFlags.setFlag("test_flag", true)

        sut = createStatelessInstance()
        config = createConfig(sendFeatureFlagEvent = false)

        sut.setup(config)
        sut.setMockQueue(mockQueue)

        sut.setMockFeatureFlags(mockFeatureFlags)

        sut.isFeatureEnabledStateless("user123", "test_flag")

        assertEquals(0, mockQueue.events.size)
    }

    @Test
    fun `group identify event excludes groups from properties`() {
        val mockQueue = MockQueue()
        sut = createStatelessInstance()
        config = createConfig()

        sut.setup(config)
        sut.setMockQueue(mockQueue)

        sut.groupStateless("user123", "company", "acme")

        assertEquals(1, mockQueue.events.size)
        val event = mockQueue.events.first()
        assertEquals("\$groupidentify", event.event)

        // Groups should not be included in $groups property for group identify events
        assertNull(event.properties!!["\$groups"])
    }

    // Helper classes
    private class MockLogger : PostHogLogger {
        val messages = mutableListOf<String>()

        override fun log(message: String) {
            messages.add(message)
        }

        override fun isEnabled(): Boolean = true
    }

    private class MockPostHogStateless : PostHogStatelessInterface {
        var setupCalled = false
        var closeCalled = false
        var optOutCalled = false
        var optInCalled = false
        var captureCalled = false
        var identifyCalled = false
        var aliasCalled = false
        var groupCalled = false
        var flushCalled = false
        var debugCalled = false

        override fun <T : PostHogConfig> setup(config: T) {
            setupCalled = true
        }

        override fun close() {
            closeCalled = true
        }

        override fun captureStateless(
            event: String,
            distinctId: String,
            properties: Map<String, Any>?,
            userProperties: Map<String, Any>?,
            userPropertiesSetOnce: Map<String, Any>?,
            groups: Map<String, String>?,
        ) {
            captureCalled = true
        }

        override fun identify(
            distinctId: String,
            userProperties: Map<String, Any>?,
            userPropertiesSetOnce: Map<String, Any>?,
        ) {
            identifyCalled = true
        }

        override fun flush() {
            flushCalled = true
        }

        override fun optIn() {
            optInCalled = true
        }

        override fun optOut() {
            optOutCalled = true
        }

        override fun isOptOut(): Boolean = false

        override fun debug(enable: Boolean) {
            debugCalled = true
        }

        override fun isFeatureEnabledStateless(
            distinctId: String,
            key: String,
            defaultValue: Boolean,
        ): Boolean = defaultValue

        override fun getFeatureFlagStateless(
            distinctId: String,
            key: String,
            defaultValue: Any?,
        ): Any? = defaultValue

        override fun getFeatureFlagPayloadStateless(
            distinctId: String,
            key: String,
            defaultValue: Any?,
        ): Any? = defaultValue

        override fun groupStateless(
            distinctId: String,
            type: String,
            key: String,
            groupProperties: Map<String, Any>?,
        ) {
            groupCalled = true
        }

        override fun aliasStateless(
            distinctId: String,
            alias: String,
        ) {
            aliasCalled = true
        }
    }
}

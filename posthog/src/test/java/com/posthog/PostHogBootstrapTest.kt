package com.posthog

import com.posthog.internal.PostHogBatchEvent
import com.posthog.internal.PostHogMemoryPreferences
import com.posthog.internal.PostHogPreferences.Companion.ANONYMOUS_ID
import com.posthog.internal.PostHogPreferences.Companion.DISTINCT_ID
import com.posthog.internal.PostHogPreferences.Companion.IS_IDENTIFIED
import com.posthog.internal.PostHogSerializer
import com.posthog.internal.PostHogThreadFactory
import okhttp3.mockwebserver.MockResponse
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

internal class PostHogBootstrapTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    private val queueExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestQueue"))
    private val replayQueueExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestReplayQueue"))
    private val remoteConfigExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestRemoteConfig"))
    private val cachedEventsExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestCachedEvents"))
    private val serializer = PostHogSerializer(PostHogConfig(API_KEY))
    private lateinit var config: PostHogConfig

    @Suppress("DEPRECATION")
    private fun getSut(
        host: String = PostHogConfig.DEFAULT_HOST,
        bootstrap: PostHogBootstrapConfig? = null,
        cachePreferences: PostHogMemoryPreferences = PostHogMemoryPreferences(),
        preloadFeatureFlags: Boolean = false,
        reloadFeatureFlags: Boolean = true,
        sendFeatureFlagEvent: Boolean = true,
        flushAt: Int = 1,
        storagePrefix: String = tmpDir.newFolder().absolutePath,
        logger: TestLogger? = null,
        optOut: Boolean = false,
    ): PostHogInterface {
        config =
            PostHogConfig(API_KEY, host).apply {
                this.flushAt = flushAt
                this.storagePrefix = File(storagePrefix, "events").absolutePath
                this.replayStoragePrefix = File(storagePrefix, "snapshots").absolutePath
                this.preloadFeatureFlags = preloadFeatureFlags
                this.sendFeatureFlagEvent = sendFeatureFlagEvent
                this.cachePreferences = cachePreferences
                // keep setup on the single /flags path instead of the /config + /flags path
                this.remoteConfig = false
                this.bootstrap = bootstrap
                this.optOut = optOut
                if (logger != null) {
                    this.logger = logger
                }
            }
        return PostHog.withInternal(
            config,
            queueExecutor,
            replayQueueExecutor,
            remoteConfigExecutor,
            cachedEventsExecutor,
            reloadFeatureFlags,
        )
    }

    @AfterTest
    fun `set down`() {
        tmpDir.root.deleteRecursively()
    }

    @Test
    fun `bootstrapped anonymous identity seeds a fresh install`() {
        val prefs = PostHogMemoryPreferences()
        val sut = getSut(bootstrap = PostHogBootstrapConfig(distinctId = "anon-abc"), cachePreferences = prefs)

        assertEquals("anon-abc", sut.getAnonymousId())
        assertEquals("anon-abc", sut.distinctId())
        assertNotEquals(true, prefs.getValue(IS_IDENTIFIED))

        sut.close()
    }

    @Test
    fun `bootstrapped identified identity marks the user identified`() {
        val prefs = PostHogMemoryPreferences()
        val sut =
            getSut(
                bootstrap = PostHogBootstrapConfig(distinctId = "user-123", isIdentifiedId = true),
                cachePreferences = prefs,
            )

        assertEquals("user-123", sut.distinctId())
        assertEquals(true, prefs.getValue(IS_IDENTIFIED))
        // an identified bootstrap must NOT reuse the user id as the anonymous / device id: those
        // survive reset() and would otherwise leak the prior user's id onto later users.
        assertNotEquals("user-123", sut.getAnonymousId())
        assertNotEquals("user-123", sut.getDeviceId())

        sut.close()
    }

    @Test
    fun `bootstrapped identity does not overwrite a persisted anonymous id`() {
        val prefs = PostHogMemoryPreferences()
        prefs.setValue(ANONYMOUS_ID, "existing-anon")
        val sut = getSut(bootstrap = PostHogBootstrapConfig(distinctId = "user-123"), cachePreferences = prefs)

        assertEquals("existing-anon", sut.getAnonymousId())
        assertEquals("existing-anon", sut.distinctId())

        sut.close()
    }

    @Test
    fun `identified bootstrap preserves a different identified user and warns`() {
        val prefs = PostHogMemoryPreferences()
        prefs.setValue(DISTINCT_ID, "user-existing")
        prefs.setValue(IS_IDENTIFIED, true)
        val logger = TestLogger()
        val sut =
            getSut(
                bootstrap = PostHogBootstrapConfig(distinctId = "user-123", isIdentifiedId = true),
                cachePreferences = prefs,
                logger = logger,
            )

        assertEquals("user-existing", sut.distinctId())
        assertTrue(logger.messages.any { it.contains("existing identity is preserved") })

        sut.close()
    }

    @Test
    fun `identified bootstrap for the same already-identified user does not warn`() {
        val prefs = PostHogMemoryPreferences()
        prefs.setValue(DISTINCT_ID, "user-123")
        prefs.setValue(IS_IDENTIFIED, true)
        val logger = TestLogger()
        val sut =
            getSut(
                bootstrap = PostHogBootstrapConfig(distinctId = "user-123", isIdentifiedId = true),
                cachePreferences = prefs,
                logger = logger,
            )

        // same id as the persisted identity: reconciliation is a no-op, no spurious warning
        assertEquals("user-123", sut.distinctId())
        assertFalse(logger.messages.any { it.contains("existing identity is preserved") })

        sut.close()
    }

    @Test
    fun `identified bootstrap merges an existing anonymous user`() {
        val http = mockHttp()
        val prefs = PostHogMemoryPreferences()
        prefs.setValue(ANONYMOUS_ID, "anon-abc")
        val sut =
            getSut(
                host = http.url("/").toString(),
                bootstrap = PostHogBootstrapConfig(distinctId = "user-123", isIdentifiedId = true),
                cachePreferences = prefs,
                reloadFeatureFlags = false,
            )

        assertEquals("user-123", sut.distinctId())
        assertEquals("anon-abc", sut.getAnonymousId())

        queueExecutor.shutdownAndAwaitTermination()
        val identify = firstEvent(http, "\$identify")
        assertEquals("user-123", identify.distinctId)
        assertEquals("anon-abc", identify.properties?.get("\$anon_distinct_id"))

        sut.close()
        http.shutdown()
    }

    @Test
    fun `opted-out identified bootstrap still merges locally, matching posthog-js`() {
        val http = mockHttp()
        val prefs = PostHogMemoryPreferences()
        prefs.setValue(ANONYMOUS_ID, "anon-abc")
        val sut =
            getSut(
                host = http.url("/").toString(),
                bootstrap = PostHogBootstrapConfig(distinctId = "user-123", isIdentifiedId = true),
                cachePreferences = prefs,
                reloadFeatureFlags = false,
                optOut = true,
            )

        // Opt-out suppresses event sending, not local identity: the merge still updates identity
        // (matching posthog-js); only the $identify event is dropped by the opt-out capture guard.
        assertEquals("user-123", sut.distinctId())
        assertEquals("anon-abc", sut.getAnonymousId())
        assertEquals(true, prefs.getValue(IS_IDENTIFIED))

        sut.close()
        http.shutdown()
    }

    @Test
    fun `blank bootstrap distinct id is a no-op`() {
        val sut = getSut(bootstrap = PostHogBootstrapConfig(distinctId = "   "))

        // a normal generated anonymous id is used, not the blank bootstrap value
        assertTrue(sut.getAnonymousId().isNotBlank())
        assertNotEquals("   ", sut.getAnonymousId())

        sut.close()
    }

    @Test
    fun `no bootstrap leaves identity untouched`() {
        val sut = getSut(bootstrap = null)

        // a generated anonymous id, and distinct id falls back to it
        val anonymousId = sut.getAnonymousId()
        assertTrue(anonymousId.isNotBlank())
        assertEquals(anonymousId, sut.distinctId())

        sut.close()
    }

    @Test
    fun `flag call before a flags response reports bootstrap use`() {
        val http = mockHttp()
        val sut =
            getSut(
                host = http.url("/").toString(),
                bootstrap =
                    PostHogBootstrapConfig(
                        featureFlags = mapOf("beta-ui" to true),
                        featureFlagPayloads = mapOf("beta-ui" to mapOf("color" to "blue")),
                    ),
            )

        // served straight from bootstrap, before any /flags response
        val value = sut.getFeatureFlag("beta-ui", sendFeatureFlagEvent = true)
        assertEquals(true, value)

        queueExecutor.shutdownAndAwaitTermination()

        val event = firstFeatureFlagCalled(http)
        assertEquals(true, event.properties?.get("\$feature_flag_response"))
        assertEquals(true, event.properties?.get("\$feature_flag_bootstrapped_response"))
        assertEquals(mapOf("color" to "blue"), event.properties?.get("\$feature_flag_bootstrapped_payload"))
        assertEquals(true, event.properties?.get("\$used_bootstrap_value"))

        sut.close()
        http.shutdown()
    }

    @Test
    fun `flag call after a flags response reports bootstrap not used`() {
        // fixture returns featureFlags {"4535-funnel-bar-viz": true}; bootstrap the same key as false
        val flags = File("src/test/resources/json/flags-v1/basic-flags-no-errors.json").readText()
        val http = mockHttp(response = MockResponse().setBody(flags))
        http.enqueue(MockResponse().setBody(""))
        val sut =
            getSut(
                host = http.url("/").toString(),
                bootstrap = PostHogBootstrapConfig(featureFlags = mapOf("4535-funnel-bar-viz" to false)),
            )

        sut.reloadFeatureFlags()
        remoteConfigExecutor.shutdownAndAwaitTermination()
        http.takeRequest() // drop the /flags request

        val value = sut.getFeatureFlag("4535-funnel-bar-viz", sendFeatureFlagEvent = true)
        assertEquals(true, value) // loaded value wins over the bootstrapped one

        queueExecutor.shutdownAndAwaitTermination()

        val event = firstFeatureFlagCalled(http)
        assertEquals(true, event.properties?.get("\$feature_flag_response"))
        // the bootstrapped value is still reported for context
        assertEquals(false, event.properties?.get("\$feature_flag_bootstrapped_response"))
        // but a real /flags response has arrived, so bootstrap was not used
        assertEquals(false, event.properties?.get("\$used_bootstrap_value"))

        sut.close()
        http.shutdown()
    }

    private fun firstEvent(
        http: okhttp3.mockwebserver.MockWebServer,
        event: String,
    ): PostHogEvent {
        val batch = serializer.deserialize<PostHogBatchEvent>(http.takeRequest().body.unGzip().reader())!!
        return batch.batch.first { it.event == event }
    }

    private fun firstFeatureFlagCalled(http: okhttp3.mockwebserver.MockWebServer): PostHogEvent = firstEvent(http, "\$feature_flag_called")
}

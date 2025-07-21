package com.posthog

import com.posthog.internal.PostHogBatchEvent
import com.posthog.internal.PostHogMemoryPreferences
import com.posthog.internal.PostHogSerializer
import com.posthog.internal.PostHogThreadFactory
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals

internal class PostHogBeforeSendTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    private val queueExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestQueue"))
    private val replayQueueExecutor =
        Executors.newSingleThreadScheduledExecutor(
            PostHogThreadFactory("TestReplayQueue"),
        )
    private val remoteConfigExecutor =
        Executors.newSingleThreadScheduledExecutor(
            PostHogThreadFactory("TestRemoteConfig"),
        )
    private val cachedEventsExecutor =
        Executors.newSingleThreadScheduledExecutor(
            PostHogThreadFactory("TestCachedEvents"),
        )
    private val serializer = PostHogSerializer(PostHogConfig(API_KEY))
    private lateinit var config: PostHogConfig

    data class BeforeSendTestEventModel(
        val targetKey: String,
        val trigger: (PostHogInterface) -> Unit,
    )

    fun getSut(
        host: String,
        flushAt: Int = 1,
        storagePrefix: String = tmpDir.newFolder().absolutePath,
        optOut: Boolean = false,
        preloadFeatureFlags: Boolean = false,
        reloadFeatureFlags: Boolean = true,
        sendFeatureFlagEvent: Boolean = true,
        reuseAnonymousId: Boolean = false,
        integration: PostHogIntegration? = null,
        remoteConfig: Boolean = false,
        cachePreferences: PostHogMemoryPreferences = PostHogMemoryPreferences(),
        listBeforeSend: List<PostHogBeforeSend>? = null,
    ): PostHogInterface {
        config =
            PostHogConfig(API_KEY, host).apply {
                // for testing
                this.flushAt = flushAt
                this.storagePrefix = storagePrefix
                this.optOut = optOut
                this.preloadFeatureFlags = preloadFeatureFlags
                if (integration != null) {
                    addIntegration(integration)
                }
                this.sendFeatureFlagEvent = sendFeatureFlagEvent
                this.reuseAnonymousId = reuseAnonymousId
                this.cachePreferences = cachePreferences
                this.remoteConfig = remoteConfig
                listBeforeSend?.map {
                    addBeforeSend(it)
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

    private val listEvents =
        listOf(
            BeforeSendTestEventModel(
                targetKey = "test_event",
                trigger = {
                    it.capture("test_event")
                },
            ),
            BeforeSendTestEventModel(
                targetKey = PostHogEventName.SCREEN.event,
                trigger = {
                    it.screen("screen")
                },
            ),
            BeforeSendTestEventModel(
                targetKey = PostHogEventName.SNAPSHOT.event,
                trigger = {
                    it.capture(PostHogEventName.SNAPSHOT.event)
                },
            ),
            BeforeSendTestEventModel(
                targetKey = PostHogEventName.IDENTIFY.event,
                trigger = {
                    it.identify(distinctId = "user_id")
                },
            ),
            BeforeSendTestEventModel(
                targetKey = PostHogEventName.GROUP_IDENTIFY.event,
                trigger = {
                    it.group("type", "key")
                },
            ),
            BeforeSendTestEventModel(
                targetKey = PostHogEventName.CREATE_ALIAS.event,
                trigger = {
                    it.alias("alias")
                },
            ),
            BeforeSendTestEventModel(
                targetKey = PostHogEventName.FEATURE_FLAG_CALLED.event,
                trigger = {
                    it.getFeatureFlag("key")
                },
            ),
        )

    @Test
    fun `drop events`() {
        for (model in listEvents) {
            val http = mockHttp()
            val url = http.url("/")
            val postHogInterface =
                getSut(
                    url.toString(),
                    listBeforeSend =
                        listOf(
                            PostHogBeforeSend { event ->
                                if (event.event == model.targetKey) {
                                    null
                                } else {
                                    event
                                }
                            },
                        ),
                )

            model.trigger(postHogInterface)

            queueExecutor.shutdownAndAwaitTermination()
            replayQueueExecutor.shutdownAndAwaitTermination()

            assertEquals(0, http.requestCount)
            postHogInterface.close()
        }
    }

    @Test
    fun `drop events with copy`() {
        val http = mockHttp()
        val url = http.url("/")
        val postHogInterface: PostHogInterface =
            getSut(
                url.toString(),
                listBeforeSend =
                    listOf(
                        PostHogBeforeSend { event ->
                            event.copy(event = PostHogEventName.SCREEN.event)
                        },
                    ),
            )
        postHogInterface.getFeatureFlag("key")

        queueExecutor.shutdownAndAwaitTermination()
        replayQueueExecutor.shutdownAndAwaitTermination()

        assertEquals(1, http.requestCount)
        postHogInterface.close()
    }

    @Test
    fun `mutate event properties`() {
        val http = mockHttp()
        val url = http.url("/")
        val postHogInterface: PostHogInterface =
            getSut(
                url.toString(),
                listBeforeSend =
                    listOf(
                        PostHogBeforeSend { event ->
                            event.properties?.set("key", "value")
                            event
                        },
                    ),
            )
        postHogInterface.getFeatureFlag("key")

        queueExecutor.shutdownAndAwaitTermination()
        replayQueueExecutor.shutdownAndAwaitTermination()

        val request = http.takeRequest()
        assertEquals(1, http.requestCount)
        val content = request.body.unGzip()
        val batch = serializer.deserialize<PostHogBatchEvent>(content.reader())

        val theEvent = batch.batch.first()

        assertEquals("value", theEvent.properties?.get("key"))

        postHogInterface.close()
    }
}

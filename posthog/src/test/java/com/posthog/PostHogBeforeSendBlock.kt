package com.posthog

import com.posthog.internal.PostHogMemoryPreferences
import com.posthog.internal.PostHogThreadFactory
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals

internal class PostHogBeforeSendBlock {

    @get:Rule
    val tmpDir = TemporaryFolder()

    private val queueExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestQueue"))
    private val replayQueueExecutor = Executors.newSingleThreadScheduledExecutor(
        PostHogThreadFactory("TestReplayQueue")
    )
    private val remoteConfigExecutor = Executors.newSingleThreadScheduledExecutor(
        PostHogThreadFactory("TestRemoteConfig")
    )
    private val cachedEventsExecutor = Executors.newSingleThreadScheduledExecutor(
        PostHogThreadFactory("TestCachedEvents")
    )
    private lateinit var config: PostHogConfig

    data class BeforeSendTestEventModel(
        val targetKey: String,
        val trigger:(PostHogInterface) -> Unit
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
        propertiesSanitizer: PostHogPropertiesSanitizer? = null,
        beforeSendBlock: (PostHogEvent) -> PostHogEvent? = { it }
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
                this.propertiesSanitizer = propertiesSanitizer
                this.remoteConfig = remoteConfig
                this.beforeSendBlock = beforeSendBlock
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

    private val listEvents = listOf(
        BeforeSendTestEventModel(
            targetKey = "test_event",
            trigger = {
                it.capture("test_event")
            }),
        BeforeSendTestEventModel(
            targetKey = "\$screen",
            trigger = {
                it.screen("screen")
            }),
        BeforeSendTestEventModel(
            targetKey = "\$snapshot",
            trigger = {
                it.capture("\$snapshot")
            }),
        BeforeSendTestEventModel(
            targetKey = "\$identify",
            trigger = {
                it.identify(distinctId = "user_id")
            }),
        BeforeSendTestEventModel(
            targetKey = "\$groupidentify",
            trigger = {
                it.group("type", "key")
            }),
        BeforeSendTestEventModel(
            targetKey = "\$create_alias",
            trigger = {
                it.alias("alias")
            }),
        BeforeSendTestEventModel(
            targetKey = "\$feature_flag_called",
            trigger = {
                it.getFeatureFlag("key")
            }))

    @Test
    fun `drop events`() {
        val http = mockHttp()
        val url = http.url("/")

        for (model in listEvents){
            val sut = getSut(url.toString(), beforeSendBlock = {
                if(it.event == model.targetKey){
                    null
                }else{
                    it
                }
            })

            model.trigger(sut)
            sut.capture(
                "single_not_drop_event",
                DISTINCT_ID,
                props,
                userProperties = userProps,
                userPropertiesSetOnce = userPropsOnce,
                groups = groups)

            queueExecutor.shutdownAndAwaitTermination()
            assertEquals(1, http.requestCount)
            sut.close()
        }
    }
}
package com.posthog.compliance

import com.posthog.PostHog
import com.posthog.PostHogConfig
import com.posthog.PostHogInterface
import com.posthog.internal.PostHogContext
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

fun sdkVersion(profile: String): String =
    java.util.Properties().apply {
        SdkProfile::class.java.getResourceAsStream("/sdk-versions.properties").use { load(it) }
    }.getProperty(profile)

fun InitRequest.flushSeconds(): Int = maxOf(1, ((flush_interval_ms ?: 500) + 999) / 1000)

class ReloadCompletion(private val config: PostHogConfig) {
    fun await(action: (com.posthog.PostHogOnFeatureFlags) -> Unit) {
        val latch = CountDownLatch(1)
        val callback = com.posthog.PostHogOnFeatureFlags { latch.countDown() }
        config.onFeatureFlags = callback
        try {
            action(callback)
            check(latch.await(15, TimeUnit.SECONDS)) { "Feature flag reload did not complete" }
        } finally {
            config.onFeatureFlags = null
        }
    }
}

fun configureStateful(
    config: PostHogConfig,
    request: InitRequest,
    observer: Observation,
): ReloadCompletion {
    config.flushAt = request.flush_at ?: 100
    config.flushIntervalSeconds = request.flushSeconds()
    config.preloadFeatureFlags = false
    request.max_retries?.let { config.maxRetries = it }
    config.addBeforeSend(observer.beforeSend)
    return ReloadCompletion(config)
}

class StatefulClient(
    private val sdk: PostHogInterface,
    private val observer: Observation,
    private val completion: ReloadCompletion,
) : SdkClient {
    private val groups = mutableMapOf<String, String>()

    override fun capture(request: CaptureRequest) {
        observer.track {
            sdk.capture(
                event = request.event,
                distinctId = request.distinct_id,
                properties = request.properties,
                timestamp = request.date(),
            )
        }
    }

    override fun flag(request: FlagRequest): Any? {
        request.person_properties?.let { sdk.setPersonPropertiesForFlags(it, reloadFeatureFlags = false) }
        request.group_properties?.forEach { (type, properties) ->
            sdk.setGroupPropertiesForFlags(type, properties, reloadFeatureFlags = false)
        }
        var reloaded = false
        request.groups?.forEach { (type, key) ->
            if (groups[type] != key) {
                // The first group assignment does not trigger an SDK reload; subsequent
                // assignments do. Defer the first load until identity is set below.
                if (groups.isEmpty()) {
                    observer.track { sdk.group(type, key) }
                } else {
                    completion.await { observer.track { sdk.group(type, key) } }
                    reloaded = true
                }
                groups[type] = key
            }
        }
        request.distinct_id?.let { distinctId ->
            if (sdk.distinctId() != distinctId) {
                completion.await { observer.track { sdk.identify(distinctId) } }
                reloaded = true
            }
        }
        // identify/group already reload through the public SDK. Await that load rather than
        // consuming a second response with a redundant reload. Ordinary reads reload explicitly.
        if (!reloaded) completion.await { sdk.reloadFeatureFlags(it) }
        return observer.track { sdk.getFeatureFlag(request.key) }
    }

    override fun flush() = sdk.flush()

    override fun close() = sdk.close()
}

object CoreProfile : SdkProfile {
    override val name = "posthog-core-integration"
    override val version = PostHogConfig("").sdkVersion

    override fun create(
        request: InitRequest,
        storage: File,
        observer: Observation,
    ): SdkClient {
        val config = PostHogConfig(request.api_key, request.host)
        val completion = configureStateful(config, request, observer)
        config.storagePrefix = storage.absolutePath
        config.context =
            object : PostHogContext {
                override fun getStaticContext(): Map<String, Any> = emptyMap()

                override fun getDynamicContext(): Map<String, Any> = emptyMap()

                override fun getSdkInfo(): Map<String, Any> = mapOf("\$lib" to config.sdkName, "\$lib_version" to config.sdkVersion)
            }
        return StatefulClient(PostHog.with(config), observer, completion)
    }
}

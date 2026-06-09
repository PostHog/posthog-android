package com.posthog.server

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * Shared cache provider for local-evaluation feature flag definitions.
 *
 * Implementations usually coordinate leadership across SDK instances. When
 * [shouldFetchFlagDefinitions] returns true this instance fetches definitions from
 * PostHog and receives them through [onFlagDefinitionsReceived]. When it returns
 * false this instance reads definitions from [getFlagDefinitions] instead.
 *
 * Provider methods return [CompletionStage] so implementations can use either blocking
 * backends or async clients such as Reactor `Mono.toFuture()`; `Flux` should be reduced
 * to one result first, for example with `next()` or `collectList()`. The SDK waits for
 * these stages in its current synchronous flag-loading flow.
 *
 * Provider errors are handled defensively by the SDK: failed reads fall back to the
 * API only when no definitions are already loaded, and failed writes/shutdowns are
 * logged without failing flag evaluation.
 */
public interface PostHogFlagDefinitionCacheProvider {
    /**
     * Return cached flag definitions, or null when the cache is empty or unavailable.
     *
     * The data should use the shared local-evaluation definitions shape returned by PostHog's
     * `/flags/definitions` endpoint: `flags`, `group_type_mapping`, and `cohorts`.
     */
    public fun getFlagDefinitions(): CompletionStage<Map<String, Any?>?>

    /**
     * Return true when this SDK instance should fetch definitions from PostHog.
     */
    public fun shouldFetchFlagDefinitions(): CompletionStage<Boolean>

    /**
     * Called with flag definitions after this SDK instance successfully fetches fresh definitions from PostHog.
     *
     * Implementations are responsible for serializing and storing this data in their cache backend.
     */
    public fun onFlagDefinitionsReceived(data: Map<String, Any?>): CompletionStage<Void?>

    /**
     * Clean up any resources held by the provider, such as distributed locks.
     */
    public fun shutdown(): CompletionStage<Void?>
}

/**
 * Blocking convenience base class for [PostHogFlagDefinitionCacheProvider].
 *
 * Extend this when your cache backend is synchronous. Async implementations should
 * implement [PostHogFlagDefinitionCacheProvider] directly and return their own
 * [CompletionStage] values.
 */
public abstract class PostHogBlockingFlagDefinitionCacheProvider : PostHogFlagDefinitionCacheProvider {
    /**
     * Return cached flag definitions, or null when the cache is empty or unavailable.
     */
    public abstract fun getFlagDefinitionsBlocking(): Map<String, Any?>?

    /**
     * Return true when this SDK instance should fetch definitions from PostHog.
     */
    public abstract fun shouldFetchFlagDefinitionsBlocking(): Boolean

    /**
     * Store freshly fetched definitions in the backing cache.
     */
    public abstract fun onFlagDefinitionsReceivedBlocking(data: Map<String, Any?>): Unit

    /**
     * Clean up any resources held by the provider, such as distributed locks.
     */
    public open fun shutdownBlocking() {
    }

    public final override fun getFlagDefinitions(): CompletionStage<Map<String, Any?>?> =
        CompletableFuture.completedFuture(getFlagDefinitionsBlocking())

    public final override fun shouldFetchFlagDefinitions(): CompletionStage<Boolean> =
        CompletableFuture.completedFuture(shouldFetchFlagDefinitionsBlocking())

    public final override fun onFlagDefinitionsReceived(data: Map<String, Any?>): CompletionStage<Void?> {
        onFlagDefinitionsReceivedBlocking(data)
        return CompletableFuture.completedFuture<Void?>(null)
    }

    public final override fun shutdown(): CompletionStage<Void?> {
        shutdownBlocking()
        return CompletableFuture.completedFuture<Void?>(null)
    }
}

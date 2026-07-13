package com.posthog

/**
 * Pre-seeded identity and feature-flag state applied on the very first SDK launch,
 * before any network request completes.
 *
 * Set [PostHogConfig.bootstrap] before calling setup to seed the distinct id and feature
 * flag state. Events captured synchronously during initialization then carry a
 * caller-controlled `$distinct_id` rather than the SDK-generated UUID, and feature-flag
 * reads return caller-provided values before the first `/flags` response. Mirrors the
 * [bootstrap option in posthog-js](https://posthog.com/docs/feature-flags/bootstrapping).
 *
 * Bootstrap only seeds the very first session. Once an anonymous id is persisted on disk,
 * or the user is already identified, the bootstrapped identity is ignored — it never
 * overrides an already-identified user or re-links traffic across a previous
 * anon→identified merge. Bootstrapped feature flags form a base layer only: values from
 * `/flags` overlay them for overlapping keys, while bootstrapped-only keys remain available.
 */
public class PostHogBootstrap
    @JvmOverloads
    constructor(
        /**
         * The distinct id to seed on first launch.
         *
         * When [isIdentifiedId] is `false` (the default) this becomes the anonymous id — the
         * `$distinct_id` on pre-identify events. When `true` it is treated as an
         * already-identified user's distinct id and the SDK marks the user identified without
         * an `$identify` merge.
         */
        public val distinctId: String? = null,
        /**
         * Whether [distinctId] represents an already-identified user.
         *
         * Defaults to `false`. Set to `true` when the host application resolved the user's
         * identity outside the SDK (for example from a backend session token) and wants the
         * SDK to treat them as identified from the first event onward.
         */
        public val isIdentifiedId: Boolean = false,
        /**
         * Feature flag values served until the first `/flags` response arrives, keyed by flag
         * key. Each value is a [Boolean] for boolean flags or a [String] for multivariate flags.
         */
        public val featureFlags: Map<String, Any>? = null,
        /**
         * JSON payloads paired with [featureFlags], keyed by flag key. Each value is the
         * already-decoded payload (map, list, string, number, ...).
         */
        public val featureFlagPayloads: Map<String, Any?>? = null,
    )

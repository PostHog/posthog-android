package com.posthog

/**
 * Pre-seeded identity and feature-flag state applied before any network request completes:
 * identity only on the very first SDK launch, and the feature-flag base layer on every launch
 * until `reset()`.
 *
 * Set [PostHogConfig.bootstrap] before calling setup to seed the distinct id and feature
 * flag state. Events captured synchronously during initialization then carry a
 * caller-controlled `$distinct_id` rather than the SDK-generated UUID, and feature-flag
 * reads return caller-provided values before the first `/flags` response. Mirrors the
 * [bootstrap option in posthog-js](https://posthog.com/docs/feature-flags/bootstrapping).
 *
 * Bootstrap identity applies to the very first session. An anonymous bootstrap
 * ([isIdentifiedId] false) seeds the anonymous id only when none is persisted, and is
 * otherwise ignored. An identified bootstrap ([isIdentifiedId] true) seeds the distinct id on
 * a fresh install; if a local anonymous user already exists it is merged into the identified
 * id via `identify()`, while a different already-identified user is preserved and a warning is
 * logged. Bootstrapped feature flags form a base layer only: values from `/flags` overlay them
 * for overlapping keys, while bootstrapped-only keys remain available.
 */
public class PostHogBootstrapConfig
    @JvmOverloads
    constructor(
        /**
         * The distinct id to seed on first launch.
         *
         * When [isIdentifiedId] is `false` (the default) this becomes the anonymous id — the
         * `$distinct_id` on pre-identify events. When `true` it is treated as an
         * already-identified user's distinct id. See the class documentation for how each mode is
         * applied.
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
         * Only enabled values are served — `true` or a non-empty [String]; a `false` or
         * empty-string entry is treated as not bootstrapped (matching posthog-js's `!!value`
         * check) and dropped, indistinguishable from a key that was never provided.
         * Values must be JSON-serializable; any non-serializable entry (e.g. NaN) is dropped with
         * a logged warning rather than surfaced.
         */
        public val featureFlags: Map<String, Any>? = null,
        /**
         * JSON payloads paired with [featureFlags], keyed by flag key. Each value is the
         * already-decoded payload (map, list, string, number, ...).
         */
        public val featureFlagPayloads: Map<String, Any?>? = null,
    )

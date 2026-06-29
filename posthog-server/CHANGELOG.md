## Next

## 2.7.2

### Patch Changes

- 15c39fd: Retry feature flag requests after transient network errors only. The feature flag request retry count defaults to 1 and can be set to 0 to disable retries.

## 2.7.1

### Patch Changes

- 125f724: Clear the feature flag called cache when closing the SDK.

## 2.7.0

### Minor Changes

- 1965fd1: Add the ability to integrate custom caching for feature flag definitions in the server SDK.

  This introduces an async-capable `PostHogFlagDefinitionCacheProvider` public API and a `PostHogBlockingFlagDefinitionCacheProvider` base class for synchronous cache backends.

## 2.6.3

### Patch Changes

- 27f8f5f: Refactor duplicated internal code paths without changing SDK behavior.

## 2.6.2

### Patch Changes

- 875e972: Improve public API KDoc coverage.

## 2.6.1

### Patch Changes

- 3deea3d: Include group context in the `$feature_flag_called` LRU dedupe key so group-scoped flags fire a separate event for each group a user is evaluated under, instead of being dedup-ed against the first group context the same `(distinctId, flagKey, value)` was seen under. The groups are canonicalized order-independently so two equal maps built in different insertion orders still dedupe to one event.

## 2.6.0

### Minor Changes

- 1aa4328: Add request-scoped context support for server-side captures, including PostHog tracing headers, session metadata, personless fallback events, and context-aware exception capture.

## 2.5.3

### Patch Changes

- b498d90: Reject semver values with leading zeros in local flag evaluation. Per semver 2.0.0 §2, numeric identifiers must not include leading zeros — values like `1.07.3` are not valid semver and should not match targeting conditions. Both override values and flag values are now validated; invalid inputs raise `InconclusiveMatchException` so the condition does not match.

## 2.5.2

### Patch Changes

- 27650da: Refactor `PostHogQueue` to be generic on `Record` and introduce `EndpointSpec`
  for per-endpoint codec, send, retry policy, and runtime knobs. No behavior
  change for events or session replay; sets up future log-record support without
  duplicating queue plumbing.

## 2.5.1

### Patch Changes

- e2f9884: Disable SDK setup when the API key is empty or whitespace after trimming.

## 2.5.0

### Minor Changes

- cd5fada: Add `evaluateFlags()` API for single-call flag evaluation. Returns a `PostHogFeatureFlagEvaluations` snapshot with `isEnabled` / `getFlag` / `getFlagPayload` / `getFlagPayloadAs<T>` accessors plus `onlyAccessed()` and `only(keys)` filters. `capture()` accepts the snapshot via a new `flags` parameter to attach `$feature/<key>` properties without a second `/flags` request; user-supplied `$feature/<key>` properties win over snapshot-derived ones. `$feature_flag_called` events now include `$feature_flag_id`, `$feature_flag_version`, `$feature_flag_reason`, and propagate `$feature_flag_error` (response-level errors plus `flag_missing` for unknown keys). `flagKeys` and `disableGeoip` are forwarded to the `/flags` request body and contribute to the per-identity cache key.

  Deprecates `isFeatureEnabled`, `getFeatureFlag`, `getFeatureFlagPayload`, `getFeatureFlagResult`, and `capture(appendFeatureFlags = true)` in favour of `evaluateFlags(...)`. The legacy methods keep working unchanged; Kotlin callers see a `@Deprecated` compile-time warning (silenceable with `@Suppress("DEPRECATION")`) and the `appendFeatureFlags = true` capture path emits a one-line deprecation log. Removal targets the next major.

### Patch Changes

- a11db4b: Remove redundant equals/hashCode from FeatureFlag-related data classes.

## 2.4.1

### Patch Changes

- 840025b: Trim surrounding whitespace from API keys, personal API keys, and host config before using them.

## 2.4.0

### Minor Changes

- ecb0551: - chore: Upgrade to Kotlin 2.1.10, AGP 8.9.1, compileSdk 36, minSdk 23

## 2.3.3

### Patch Changes

- 17ba416: Add semver comparison operators to local feature flag evaluation

  This adds 9 semver operators for targeting users based on app version:

  - `semver_eq`, `semver_neq` — exact match / not equal
  - `semver_gt`, `semver_gte`, `semver_lt`, `semver_lte` — comparison operators
  - `semver_tilde` — patch-level range (~1.2.3 means >=1.2.3 <1.3.0)
  - `semver_caret` — compatible-with range (^1.2.3 means >=1.2.3 <2.0.0)
  - `semver_wildcard` — wildcard range (1.2.\* means >=1.2.0 <1.3.0)

## 2.3.2

### Patch Changes

- f86f22e: Remove `config=true` from flags endpoint, add `timezone` to flags requests, and deprecate `remoteConfig` option

## 2.3.1

### Patch Changes

- 1e73791: test new release process

## 2.3.0 - 2026-02-05

- feat: Expose `getFeatureFlagResult` to public API ([#405](https://github.com/PostHog/posthog-android/pull/405))

## 2.2.0 - 2026-01-23

- feat: Add ETag support for local evaluation polling to reduce bandwidth when flags haven't changed ([#350](https://github.com/PostHog/posthog-android/pull/350))
- feat: `$feature_flag_called` events now report `$feature_flag_error` property ([#355](https://github.com/PostHog/posthog-android/pull/355))
- feat: Add `evaluationContexts` support to `PostHogConfig` for server-side evaluation contexts ([#385](https://github.com/PostHog/posthog-android/pull/385))

## 2.1.0 - 2025-12-05

- feat: Include `evaluated_at` properties in `$feature_flag_called` events ([#321](https://github.com/PostHog/posthog-android/pull/321))
- feat: Add `appendFeatureFlags` optional boolean to `capture` ([#347](https://github.com/PostHog/posthog-android/pull/347))

## 2.0.1 - 2025-11-24

- fix: Local evaluation properly handles cases when flag dependency should be false ([#320](https://github.com/PostHog/posthog-android/pull/320))

## 2.0.0 - 2025-11-06

- feat: Add local evaluation for feature flags ([#299](https://github.com/PostHog/posthog-android/issues/299))
- feat: Add `captureException` method for error tracking ([#313](https://github.com/PostHog/posthog-android/pull/313))
- fix: Restructured `groupProperties` and `userProperties` types to match the API and other SDKs ([#312](https://github.com/PostHog/posthog-android/pull/312))

## 1.1.0 - 2025-10-03

- feat: `timestamp` can now be overridden when capturing an event ([#297](https://github.com/PostHog/posthog-android/issues/297))
- feat: Add `groups`, `groupProperties`, `personProperties` overrides to feature flag methods ([#298](https://github.com/PostHog/posthog-android/issues/298))

## 1.0.3 - 2025-10-01

- fix: Events now record SDK info such as `$lib` and `$lib_version` ([#296](https://github.com/PostHog/posthog-android/pull/296))
- fix: SDK requests now assign the expected User-Agent ([#296](https://github.com/PostHog/posthog-android/pull/296))

## 1.0.2 - 2025-09-30

- fix: Caching of feature flags occurs in constant time ([#294](https://github.com/PostHog/posthog-android/pull/294))

## 1.0.1 - 2025-09-30

- fix: Support deduplication of `$feature_flag_called` events ([#291](https://github.com/PostHog/posthog-android/pull/291))
- fix: Adds missing `featureFlagCacheSize`, `featureFlagCacheMaxAgeMs` mutators to `PostHogConfig` builder ([#291](https://github.com/PostHog/posthog-android/pull/291))

## 1.0.0 - 2025-09-29

- Initial release ([#288](https://github.com/PostHog/posthog-android/pull/288))

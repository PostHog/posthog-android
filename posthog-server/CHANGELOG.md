## Next

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

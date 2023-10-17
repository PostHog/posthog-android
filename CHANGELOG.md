## Next

- Registered keys are cached in the disk preferences ([#51](https://github.com/PostHog/posthog-android/pull/51))

## 3.0.0-alpha.8 - 2023-10-12

- SDK only sends the `$feature_flag_called` event once per flag ([#47](https://github.com/PostHog/posthog-android/pull/47))
- Groups are cached in the disk ([#48](https://github.com/PostHog/posthog-android/pull/48))

## 3.0.0-alpha.7 - 2023-10-10

- isFeatureEnabled now returns true if multivariant flag ([#42](https://github.com/PostHog/posthog-android/pull/42))
- getFeatureFlagPayload returns non strigified JSON ([#44](https://github.com/PostHog/posthog-android/pull/44))

## 3.0.0-alpha.6 - 2023-10-06

- Upsert flags when loading feature flags with computed errors ([#38](https://github.com/PostHog/posthog-android/pull/38))
- `$active_feature_flags` event should filter non active flags ([#41](https://github.com/PostHog/posthog-android/pull/41))

## 3.0.0-alpha.5 - 2023-10-04

- Does not report screen events if there's no title ([#32](https://github.com/PostHog/posthog-android/pull/32))
- Add `distinctId()` getter to the Public API ([#33](https://github.com/PostHog/posthog-android/pull/33))
- Add compatibility to Java 8 bytecode (previously Java 11+) ([#37](https://github.com/PostHog/posthog-android/pull/37))

## 3.0.0-alpha.4 - 2023-10-03

- Fixed small issues after code review

## 3.0.0-alpha.3 - 2023-10-02

- Added many tests and fixed small issues

## 3.0.0-alpha.2 - 2023-09-27

- Fix permission check for Network status ([commit](https://github.com/PostHog/posthog-android/commit/57b9626a745a37a9c92437529ba9eaf308b03771))

## 3.0.0-alpha.1 - 2023-09-26

- Next major of the Android SDK rewritten in Kotlin
- Just testing the release automation

## 2.0.3 - 2023-01-30

- Feature flags will be sent with payloads by default for capture and screen events. 

## 2.0.2 - 2023-02-21

- Revert: Feature flags will be sent with payloads by default. Default Options will be properly applied 

## 2.0.1 - 2023-01-30

- Feature flags will be sent with payloads by default. Default Options will be properly applied 

## 2.0.0 - 2022-08-29

- Add support for groups, simplefeature flags, and  multivariate feature flags

## 1.1.2 - 2021-03-11

- Fix NullPointerException in PostHogActivityLifecycleCallbacks
- Refactor properties

## 1.1.1 - 2020-07-09

- Fix a bug where the name of the event changed to `$screen` instead of the key for the event screen

## 1.1.0 - 2020-07-08

- Use `$screen_name` instead of `$screen` as key for what screen you are on when sending a .screen event

## 1.0.2 - 2020-05-20

- Use `.defaultOptions(new Options().putContext("$lib", "custom-lib"))` to pass a default context

## 1.0.1 - 2020-05-20

- Added `.getAnonymousId()` to `PostHog`

## 1.0.0 - 2020-04-29

- First version

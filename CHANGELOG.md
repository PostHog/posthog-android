## Next

- set `device_type` accordindly, Mobile, TV, Tablet ([#91](https://github.com/PostHog/posthog-android/pull/91))

## 3.1.6 - 2024-02-01

- recording: clone drawable before using ([#91](https://github.com/PostHog/posthog-android/pull/91))

## 3.1.5 - 2024-01-24

- recording: gradient background support ([#88](https://github.com/PostHog/posthog-android/pull/88))

## 3.1.4 - 2024-01-19

- Set `Content-Length` header for gzip bodies because of more strict proxies ([#86](https://github.com/PostHog/posthog-android/pull/86))

## 3.1.3 - 2024-01-17

- Do not capture console logs and network requests if session replay and session are not active ([#83](https://github.com/PostHog/posthog-android/pull/83))

## 3.1.2 - 2024-01-15

- Send wireframe children for updated wireframes - mutation ([#81](https://github.com/PostHog/posthog-android/pull/81))

## 3.1.1 - 2024-01-11

- fix: Events Queue is only paused if there were errors ([#78](https://github.com/PostHog/posthog-android/pull/78))
- fix: Do not report flag events if the flag has been reported with the same value ([#78](https://github.com/PostHog/posthog-android/pull/78))
- fix: Check keyboard status during snapshot instead of using WindowInsets listener ([#77](https://github.com/PostHog/posthog-android/pull/77))

## 3.1.0 - 2024-01-08

- chore: Add mutations support to Session Recording ([#72](https://github.com/PostHog/posthog-android/pull/72)) 
- chore: Session Recording as Experimental preview
  - Check out the [USAGE](https://github.com/PostHog/posthog-android/blob/main/USAGE.md#android-session-recording) guide.

## 3.0.1 - 2024-01-03

- `flush` forcefully sends events regardless the delay ([#73](https://github.com/PostHog/posthog-android/pull/73))

## 3.1.0-alpha.2 - 2024-01-02

- Send custom keyboard event when keyboard is open or close ([#71](https://github.com/PostHog/posthog-android/pull/71))

## 3.1.0-alpha.1 - 2023-12-18

- Android Session Recording - Alpha preview ([#69](https://github.com/PostHog/posthog-android/pull/69))

Check out the [USAGE](https://github.com/PostHog/posthog-android/blob/main/USAGE.md#android-session-recording) guide.

## 3.0.0 - 2023-12-06

Check out the updated [docs](https://posthog.com/docs/libraries/android).

Check out the [USAGE](https://github.com/PostHog/posthog-android/blob/main/USAGE.md) guide.

### Changes

- Bump Kotlin to min. 1.6 compatibility ([#68](https://github.com/PostHog/posthog-android/pull/68))
- `minSdk` set to 21.
- Rewritten in Kotlin.

## 3.0.0-RC.1 - 2023-11-20

- Do not set `$network_carrier` property if empty ([#66](https://github.com/PostHog/posthog-android/pull/66))

## 3.0.0-beta.6 - 2023-11-14

- Add a `propertiesSanitizer` callback configuration ([#64](https://github.com/PostHog/posthog-android/pull/64))

## 3.0.0-beta.5 - 2023-11-14

- Expose and allow to enable and disable the debug mode at runtime ([#60](https://github.com/PostHog/posthog-android/pull/60))
- Cache and read feature flags on the disk ([#61](https://github.com/PostHog/posthog-android/pull/61))
- Pick up consumer proguard rules correctly ([#62](https://github.com/PostHog/posthog-android/pull/62))

## 3.0.0-beta.4 - 2023-11-08

- Fix leaked resources identified by StrictMode ([#59](https://github.com/PostHog/posthog-android/pull/59))

## 3.0.0-beta.3 - 2023-11-02

- Ship proguard rules for proguard/r8 full mode ([#52](https://github.com/PostHog/posthog-android/pull/52))
- Remove properties from `identify` and `alias` ([#55](https://github.com/PostHog/posthog-android/pull/55))

## 3.0.0-beta.2 - 2023-10-20

- Keep trying to flush events till the device is connected ([#54](https://github.com/PostHog/posthog-android/pull/54))

## 3.0.0-beta.1 - 2023-10-18

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

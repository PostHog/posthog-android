## Next

## 3.26.0 - 2025-11-05

- feat: Cache properties for flag evaluation ([#315](https://github.com/PostHog/posthog-android/pull/315))

## 3.25.0 - 2025-10-23

- feat: allow override of event timestamp ([#309](https://github.com/PostHog/posthog-android/pull/309))

## 3.24.0 - 2025-10-20

- add evaluation tags to android SDK ([#301](https://github.com/PostHog/posthog-android/pull/301))
- feat: add manual captureException ([#300](https://github.com/PostHog/posthog-android/issues/300))
- feat: add exception autocapture ([#305](https://github.com/PostHog/posthog-android/issues/305))

## 3.23.0 - 2025-10-06

- feat: Android surveys use the new response question id format ([#289](https://github.com/PostHog/posthog-android/pull/289))

## 3.22.0 - 2025-09-25

- feat: Add a server-side stateless interface ([#284](https://github.com/PostHog/posthog-android/pull/284))

## 3.21.3 - 2025-09-16

- fix: throttle mechanism wasn't thread safe ([#283](https://github.com/PostHog/posthog-android/pull/283))

## 3.21.2 - 2025-09-11

- fix: prevent circular reference in the view hierarchy where a child view somehow references back to a parent, creating an infinite loop ([#281](https://github.com/PostHog/posthog-android/pull/281))

## 3.21.1 - 2025-09-04

- fix: do not throw if Canvas creation failed ([#280](https://github.com/PostHog/posthog-android/pull/280))

## 3.21.0 - 2025-08-27

- feat: add surveys support with custom UI ([#276](https://github.com/PostHog/posthog-android/pull/276))

## 3.20.4 - 2025-08-25

- fix: do not try to use the view if it is in a dirty state ([#278](https://github.com/PostHog/posthog-android/pull/278))

## 3.20.3 - 2025-08-22

- fix: do not try to create a Canvas with a recycled bitmap ([#277](https://github.com/PostHog/posthog-android/pull/277))

## 3.20.2 - 2025-08-07

- fix: missing session_id property for snapshot events ([#273](https://github.com/PostHog/posthog-android/pull/273))

## 3.20.1 - 2025-07-28

- fix: call PostHogOnFeatureFlags if remote config fails ([#270](https://github.com/PostHog/posthog-android/pull/270))

## 3.20.0 - 2025-07-23

- feat: add support for beforeSend function to edit or drop events ([#266](https://github.com/PostHog/posthog-android/pull/266))
  - Thanks @KopeikinaDarya

## 3.19.2 - 2025-07-10

- fix: enable gzip for /flags endpoint ([#268](https://github.com/PostHog/posthog-android/pull/245)) and ([#268](https://github.com/PostHog/posthog-android/pull/264))

## 3.19.1 - 2025-06-16

- test: release using new sonatype server

## 3.19.0 - 2025-06-16

- fix: start session replay after remote config and feature flags are executed during first app start ([#261](https://github.com/PostHog/posthog-android/pull/261))
- fix: make a defensive MotionEvent copy to avoid native crashes ([#262](https://github.com/PostHog/posthog-android/pull/262))
- use /flags instead of /decide ([#245](https://github.com/PostHog/posthog-android/pull/245)) and ([#264](https://github.com/PostHog/posthog-android/pull/264))

## 3.18.0 - 2025-06-12

- feat: add proxy to `PostHogConfig` ([#260](https://github.com/PostHog/posthog-android/issues/260))
  - Thanks @MamboBryan

```kotlin
val config = PostHogAndroidConfig("...")
config.proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("proxy.example.com",8080))
```

## 3.17.0 - 2025-06-05

- chore: do not capture screenshot during screen changes ([#254](https://github.com/PostHog/posthog-android/pull/254))

## 3.16.0 - 2025-05-27

- chore: manual start and stop session replay ([#248](https://github.com/PostHog/posthog-android/pull/248))

## 3.15.1 - 2025-05-27

- fix: clear feature flags cache when flags were cleared up server side ([#246](https://github.com/PostHog/posthog-android/pull/246))

## 3.15.0 - 2025-05-14

- feat: log request and payload requests if debug is enabled ([#249](https://github.com/PostHog/posthog-android/pull/249))

## 3.14.1 - 2025-04-23

- fix: Send correct `$feature_flag_response` for the `$feature_flag_called` event when calling `isFeatureEnabled` ([#244](https://github.com/PostHog/posthog-android/pull/244))
- feat: use network time instead of device time ([#235](https://github.com/PostHog/posthog-android/pull/235))

## 3.14.0 - 2025-04-21

- feat: add `$feature_flag_id`, `$feature_flag_version`, `$feature_flag_reason` and `$feature_flag_request_id` properties to `$feature_flag_called` event ([#243](https://github.com/PostHog/posthog-android/pull/243))

## 3.13.1 - 2025-04-01

- fix: add remote config response to proguard rules ([#238](https://github.com/PostHog/posthog-android/pull/238))

## 3.13.0 - 2025-03-25

- feat: use remote config API ([#233](https://github.com/PostHog/posthog-android/pull/233))

## 3.12.0 - 2025-03-10

- feat: support reuse of `anonymousId` between user changes ([#229](https://github.com/PostHog/posthog-android/pull/229))
- chore: delete queue files if serialization fails ([#232](https://github.com/PostHog/posthog-android/pull/232))

## 3.11.3 - 2025-02-26

- feat: support quota limiting for feature flags ([#228](https://github.com/PostHog/posthog-android/pull/228) and [#230](https://github.com/PostHog/posthog-android/pull/230))

## 3.11.2 - 2025-02-04

- fix: touches fall back to single touches if out of bounds ([#221](https://github.com/PostHog/posthog-android/pull/221))

## 3.11.1 - 2025-01-30

- fix: do not allow default integrations multiple times ([#219](https://github.com/PostHog/posthog-android/pull/219))

## 3.11.0 - 2025-01-27

- chore: Session Replay - GA
- fix: session replay and auto capture works with 'with' method ([#217](https://github.com/PostHog/posthog-android/pull/217))
- fix: sending cached events null check ([#218](https://github.com/PostHog/posthog-android/pull/218))

## 3.10.0 - 2025-01-07

- chore: change screenshots debouncing approach to throttling ([#214](https://github.com/PostHog/posthog-android/pull/214))
  - Added `throttleDelayMs` config and deprecated `debouncerDelayMs` config.
- chore: change screenshot image type from JPEG to WEBP ([#211](https://github.com/PostHog/posthog-android/pull/211))

## 3.9.3 - 2024-11-26

- no user facing changes

## 3.9.2 - 2024-11-12

- fix: allow changing person properties after identify ([#205](https://github.com/PostHog/posthog-android/pull/205))

## 3.9.1 - 2024-11-11

- recording: fix observation on multiple threads in layout/draw is not supported for compose ([#204](https://github.com/PostHog/posthog-android/pull/204))

## 3.9.0 - 2024-10-30

- recording: add replay masking to jetpack compose views ([#198](https://github.com/PostHog/posthog-android/pull/198))

## 3.8.3 - 2024-10-25

- recording: fix crash when calling view.isVisible ([#201](https://github.com/PostHog/posthog-android/pull/201))
- recording: change debouncerDelayMs default from 500ms to 1000ms (1s) ([#201](https://github.com/PostHog/posthog-android/pull/201))

## 3.8.2 - 2024-10-14

- recording: session replay respect feature flag variants ([#197](https://github.com/PostHog/posthog-android/pull/197))

## 3.8.1 - 2024-10-09

- recording: `OnTouchEventListener` try catch guard to swallow unexpected errors take 2 ([#196](https://github.com/PostHog/posthog-android/pull/196))

## 3.8.0 - 2024-10-03

- feat: add referrerURL automatically ([#186](https://github.com/PostHog/posthog-android/pull/186))

## 3.7.5 - 2024-09-26

- fix: isFeatureEnabled wasn't sending the $feature_flag_called event ([#185](https://github.com/PostHog/posthog-android/pull/185))

## 3.7.4 - 2024-09-23

- no user facing changes

## 3.7.3 - 2024-09-17

- no user facing changes

## 3.7.2 - 2024-09-17

- no user facing changes

## 3.7.1 - 2024-09-17

- recording: respect session replay project settings from app start ([#177](https://github.com/PostHog/posthog-android/pull/177))

## 3.7.0 - 2024-09-11

- chore: add personProfiles support ([#171](https://github.com/PostHog/posthog-android/pull/171))

## 3.6.1 - 2024-08-30

- fix: do not clear events when reset is called ([#170](https://github.com/PostHog/posthog-android/pull/170))
- fix: reload feature flags as anon user after reset is called ([#170](https://github.com/PostHog/posthog-android/pull/170))

## 3.6.0 - 2024-08-29

- recording: expose session id ([#166](https://github.com/PostHog/posthog-android/pull/166))
- fix: rotate session id when reset is called ([#168](https://github.com/PostHog/posthog-android/pull/168))
- feat: add `$is_identified` property ([#167](https://github.com/PostHog/posthog-android/pull/167))
- fix: identify should not allow already identified users ([#167](https://github.com/PostHog/posthog-android/pull/167))

## 3.5.1 - 2024-08-26

- recording: capture touch interaction off of main thread to avoid ANRs ([#165](https://github.com/PostHog/posthog-android/pull/165))
- chore: use getNetworkCapabilities instead of getNetworkInfo to avoid ANRs ([#164](https://github.com/PostHog/posthog-android/pull/164))

## 3.5.0 - 2024-08-08

- feat: add emulator detection property to static context ([#154](https://github.com/PostHog/posthog-android/pull/154))
- fix: ensure activity name is used when activity label is not defined ([#153](https://github.com/PostHog/posthog-android/pull/153)) and ([#156](https://github.com/PostHog/posthog-android/pull/156))
- recording: mask views with `contentDescription` setting and mask `WebView` if any masking is enabled ([#149](https://github.com/PostHog/posthog-android/pull/149))

## 3.4.2 - 2024-06-28

- chore: create ctor overloads for better Java DX ([#148](https://github.com/PostHog/posthog-android/pull/148))

## 3.4.1 - 2024-06-27

- recording: `OnTouchEventListener` try catch guard to swallow unexpected errors ([#147](https://github.com/PostHog/posthog-android/pull/147))

## 3.4.0 - 2024-06-20

- chore: screenshot masking ([#145](https://github.com/PostHog/posthog-android/pull/145))

## 3.3.2 - 2024-06-17

- chore: migrate UUID from v4 to v7 ([#142](https://github.com/PostHog/posthog-android/pull/142))
- recording: fix registering the ViewTreeObserver when its not attached yet ([#144](https://github.com/PostHog/posthog-android/pull/144))

## 3.3.1 - 2024-06-11

- chore: change host to new address ([#137](https://github.com/PostHog/posthog-android/pull/137))
- fix: rename groupProperties to groups for capture methods ([#139](https://github.com/PostHog/posthog-android/pull/139))
- fix: PostHogLogger possibly leaks this ctor and crash ([#140](https://github.com/PostHog/posthog-android/pull/140))

## 3.3.0 - 2024-05-24

- feat: allow anonymous uuid generation to be customizable ([#132](https://github.com/PostHog/posthog-android/pull/132))
- recording: fix removing the ViewTreeObserver when its not alive ([#134](https://github.com/PostHog/posthog-android/pull/134))

## 3.2.2 - 2024-05-21

- chore: register to sdk console ([#131](https://github.com/PostHog/posthog-android/pull/131))

## 3.2.1 - 2024-05-08

- fix: reduce batch size if API returns 413 ([#130](https://github.com/PostHog/posthog-android/pull/130))
  - `screenshot` image is now a JPEG at 30% quality to reduce size

## 3.2.0 - 2024-04-30

- recording: add `screenshot` option for session replay instead of wireframe ([#127](https://github.com/PostHog/posthog-android/pull/127))

## 3.1.18 - 2024-04-24

- fix: set correct `User-Agent` for Android and returns session recording even if authorized domains is enabled ([#125](https://github.com/PostHog/posthog-android/pull/125))

## 3.1.17 - 2024-04-11

- recording: multiple fixes for better frame rate, padding, drawables ([#118](https://github.com/PostHog/posthog-android/pull/118))

## 3.1.16 - 2024-03-27

- fix: add replay props only if replay is enabled ([#112](https://github.com/PostHog/posthog-android/pull/112))

## 3.1.15 - 2024-03-08

- recording: do not capture password text even if masking is off ([#111](https://github.com/PostHog/posthog-android/pull/111))

## 3.1.14 - 2024-03-05

- recording: fix issue with nullable hint ([#109](https://github.com/PostHog/posthog-android/pull/109))
- recording: adds `drawableConverter` option to convert custom Drawable to Bitmap ([#110](https://github.com/PostHog/posthog-android/pull/110))

## 3.1.13 - 2024-03-01

- fix do not allow nullable `sessionId` ([#107](https://github.com/PostHog/posthog-android/pull/107))

## 3.1.12 - 2024-02-29

- fix merge groups for events ([#105](https://github.com/PostHog/posthog-android/pull/105))

## 3.1.11 - 2024-02-28

- fix back compatibility with Kotlin 1.7 ([#104](https://github.com/PostHog/posthog-android/pull/104))

## 3.1.10 - 2024-02-27

- do not allow empty or blank `distinct_id` or `anon_distinct_id` ([#101](https://github.com/PostHog/posthog-android/pull/101))

## 3.1.9 - 2024-02-22

- roll back compile API to 33 to keep back compatibility ([#98](https://github.com/PostHog/posthog-android/pull/98))
- set `device_type` accordingly to Mobile, TV, or Tablet ([#93](https://github.com/PostHog/posthog-android/pull/93))

## 3.1.8 - 2024-02-19

- fix reset session when reset or close are called ([#97](https://github.com/PostHog/posthog-android/pull/97))

## 3.1.7 - 2024-02-14

- recording: fix missing windowAttachCount method after minification ([#96](https://github.com/PostHog/posthog-android/pull/96))

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
- getFeatureFlagPayload returns non stringified JSON ([#44](https://github.com/PostHog/posthog-android/pull/44))

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

- Add support for groups, simplefeature flags, and multivariate feature flags

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

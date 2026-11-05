---
title: "PostHog Android SDK 3.x"
sidebar: Docs
showTitle: true
contentMaxWidthClass: max-w-full
platformLogo: android
tableOfContents:
  - value: "Getting started"
    url: getting-started
    depth: 0
  - value: "Setup"
    url: setup
    depth: 0
  - value: "Capture"
    url: capture
    depth: 0
  - value: "Identify"
    url: identify
    depth: 0
  - value: "Logs"
    url: logs
    depth: 0
  - value: "Alias"
    url: alias
    depth: 0
  - value: "Capture exception"
    url: capture-exception
    depth: 0
  - value: "Debug"
    url: debug
    depth: 0
  - value: "Exception steps"
    url: exception-steps
    depth: 0
  - value: "Flush"
    url: flush
    depth: 0
  - value: "Get anonymous ID"
    url: get-anonymous-id
    depth: 0
  - value: "Get distinct ID"
    url: get-distinct-id
    depth: 0
  - value: "Get feature flag"
    url: get-feature-flag
    depth: 0
  - value: "Get feature flag payload"
    url: get-feature-flag-payload
    depth: 0
  - value: "Get feature flag result"
    url: get-feature-flag-result
    depth: 0
  - value: "Get feature flags"
    url: get-feature-flags
    depth: 0
  - value: "Get session ID"
    url: get-session-id
    depth: 0
  - value: "Group"
    url: group
    depth: 0
  - value: "Is feature enabled"
    url: is-feature-enabled
    depth: 0
  - value: "Is opt out"
    url: is-opt-out
    depth: 0
  - value: "Is session replay active"
    url: is-session-replay-active
    depth: 0
  - value: "Opt in"
    url: opt-in
    depth: 0
  - value: "Register"
    url: register
    depth: 0
  - value: "Reload feature flags"
    url: reload-feature-flags
    depth: 0
  - value: "Reset"
    url: reset
    depth: 0
  - value: "Reset group properties for flags"
    url: reset-group-properties-for-flags
    depth: 0
  - value: "Reset person properties for flags"
    url: reset-person-properties-for-flags
    depth: 0
  - value: "Screen"
    url: screen
    depth: 0
  - value: "Set group properties for flags"
    url: set-group-properties-for-flags
    depth: 0
  - value: "Set person properties"
    url: set-person-properties
    depth: 0
  - value: "Set person properties for flags"
    url: set-person-properties-for-flags
    depth: 0
  - value: "Shutdown"
    url: shutdown
    depth: 0
  - value: "Start session recording"
    url: start-session-recording
    depth: 0
  - value: "Stop session recording"
    url: stop-session-recording
    depth: 0
  - value: "Unregister"
    url: unregister
    depth: 0
  - value: "Device ID generator"
    url: device-id-generator
    depth: 0
  - value: "Session manager"
    url: session-manager
    depth: 0
  - value: "SDK utils"
    url: sdk-utils
    depth: 0
  - value: "PostHogAndroid"
    url: posthogandroid
    depth: 0
  - value: "Types"
    url: types
    depth: 0
---

## Getting started


Add the SDK to your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.posthog:posthog-android:<version>")
}
```

Initialize it once, in `Application.onCreate`:

```kotlin
val config = PostHogAndroidConfig("<ph_project_token>", host = "<ph_client_api_host>")
PostHogAndroid.setup(this, config)
```

Capture an event and identify the user:

```kotlin
PostHog.capture("button_clicked", properties = mapOf("plan" to "pro"))
PostHog.identify("user-123", userProperties = mapOf("email" to "max@example.com"))
```

Flush queued events before the app process ends:

```kotlin
PostHog.flush()
PostHog.close()
```

Every `PostHog.*` method below is also available on the instance that `PostHogAndroid.with(context, config)` returns.

## Setup

### `PostHogAndroid.setup`

```kotlin
fun <T : PostHogAndroidConfig> setup(context: Context, config: T)
```

Sets up the SDK and stores it as the global singleton.

#### Parameters

- `context`: Android context; the application context is retained internally.
- `config`: Android SDK configuration.

### `PostHogAndroid.with`

```kotlin
fun <T : PostHogAndroidConfig> with(context: Context, config: T): PostHogInterface
```

Creates and returns a configured SDK instance that you can hold and pass around.

Default PostHog integrations are installed only on the first instance created by either setup or with. The first instance in the host app owns those integrations, so setup order matters.

#### Return

The configured PostHog client instance.

#### Parameters

- `context`: Android context; the application context is retained internally.
- `config`: Android SDK configuration.

## Capture

### `PostHog.capture`

```kotlin
fun capture(event: String, distinctId: String? = null, properties: Map<String, Any>? = null, userProperties: Map<String, Any>? = null, userPropertiesSetOnce: Map<String, Any>? = null, groups: Map<String, String>? = null, timestamp: Date? = null)
```

Captures events

#### Parameters

- `event`: the event name
- `distinctId`: the distinctId, the generated distinctId is used if not given
- `properties`: the custom properties
- `userProperties`: the user properties, set as a "$set" property, Docs https://posthog.com/docs/product-analytics/user-properties
- `userPropertiesSetOnce`: the user properties to set only once, set as a "$set\_once" property, Docs https://posthog.com/docs/product-analytics/user-properties
- `groups`: the groups, set as a "$groups" property, Docs https://posthog.com/docs/product-analytics/group-analytics
- `timestamp`: the event timestamp override. UTC is preferred; the equivalent instant is serialized in UTC. If omitted, the current time is used

## Identify

### `PostHog.identify`

```kotlin
fun identify(distinctId: String, userProperties: Map<String, Any>? = null, userPropertiesSetOnce: Map<String, Any>? = null)
```

Identifies the user Docs https://posthog.com/docs/product-analytics/identify

#### Parameters

- `distinctId`: the distinctId
- `userProperties`: the user properties, set as a "$set" property, Docs https://posthog.com/docs/product-analytics/user-properties
- `userPropertiesSetOnce`: the user properties to set only once, set as a "$set\_once" property, Docs https://posthog.com/docs/product-analytics/user-properties

## Logs

### `PostHog.captureLog`

```kotlin
fun captureLog(message: String, severity: PostHogLogSeverity = PostHogLogSeverity.INFO, attributes: Map<String, Any>? = null, traceId: String? = null, spanId: String? = null, traceFlags: Int? = null)
```

Captures a single log record into PostHog's logs product, with optional W3C distributed-tracing correlation.

Prefer the logger facade (logger.info, .error, …) for everyday logging. Reach for this entry point when you need to correlate a log with a distributed trace by attaching traceId / spanId / traceFlags.

```kotlin
posthog.captureLog(
    "payment failed",
    severity = PostHogLogSeverity.ERROR,
    attributes = mapOf("code" to "PAY_3001"),
    traceId = "4bf92f3577b34da6a3ce929d0e0e4736",
    spanId = "00f067aa0ba902b7",
    traceFlags = 0x01,
)
```

#### Parameters

- `message`: the log message body; blank messages are dropped
- `severity`: the log severity, defaults to PostHogLogSeverity.INFO
- `attributes`: optional structured attributes; values must be JSON-serializable (`String`, `Number`, `Boolean`, `Date`, lists or maps of the same)
- `traceId`: optional 32-character lowercase hex W3C trace id. The ingestion service zeroes ids that are not exactly 16 bytes.
- `spanId`: optional 16-character lowercase hex W3C span id. The ingestion service zeroes ids that are not exactly 8 bytes.
- `traceFlags`: optional W3C trace flags bitfield (bit 0 is the `sampled` flag); emitted on the wire as `flags`.

### `PostHog.logger`

```kotlin
val logger: PostHogLogger
```

Captures application log records into PostHog's logs product (separate from product analytics events). Use the severity-specific helpers (PostHogLogger.trace, debug, info, warn, error, fatal) or the generic PostHogLogger.log.

```kotlin
posthog.logger.info("checkout opened")
posthog.logger.error("payment failed", mapOf("code" to "PAY_3001"))
```

Not to be confused with the internal `config.logger` debug sink.

## Alias

### `PostHog.alias`

```kotlin
fun alias(alias: String)
```

Creates an alias for the user Docs https://posthog.com/docs/product-analytics/identify#alias-assigning-multiple-distinct-ids-to-the-same-user

#### Parameters

- `alias`: the alias

## Capture exception

### `PostHog.captureException`

```kotlin
fun captureException(throwable: Throwable, properties: Map<String, Any>? = null)
```

Captures exceptions

#### Parameters

- `throwable`: the Throwable error
- `properties`: the custom properties

## Debug

### `PostHog.debug`

```kotlin
fun debug(enable: Boolean = true)
```

Enables or disables the debug mode

#### Parameters

- `enable`: true to enable SDK debug logging, false to disable it.

## Exception steps

### `PostHog.addExceptionStep`

```kotlin
fun addExceptionStep(message: String, properties: Map<String, Any>? = null)
```

Records a breadcrumb-style exception step. A snapshot of the recorded steps is attached to every captured `$exception` event as `$exception_steps`, giving the error-tracking UI a timeline of recent activity leading up to each error.

The buffer is a rolling window scoped to the SDK instance: it persists across captures and user identity changes, rotating only by byte-budget eviction (see com.posthog.errortracking.PostHogExceptionStepsConfig).

Recording never throws into the host app: an empty message is ignored with a warning, and internal failures skip the step (logged via the configured logger).

Recording is synchronous: the `$timestamp` is captured at call time and the step is normalized, byte-budget enforced, and buffered before this method returns, so a step recorded immediately before an exception or crash is present when it is captured. The work is bounded and cheap, adding negligible latency to the caller.

#### Parameters

- `message`: a non-empty description of the step
- `properties`: optional user-supplied properties (the reserved keys `$message` and `$timestamp` are stripped)

## Flush

### `PostHog.flush`

```kotlin
fun flush()
```

Flushes all the events in the Queue right away

## Get anonymous ID

### `PostHog.getAnonymousId`

```kotlin
fun getAnonymousId(): String
```

Returns the anonymous ID generated for this device before any identify call.

Unlike distinctId, this value does not change after identify — it always reflects the pre-identification anonymous identifier. Useful for linking anonymous sessions to identified users in downstream systems.

#### Return

The anonymous ID, or an empty string if the SDK is not enabled.

## Get distinct ID

### `PostHog.distinctId`

```kotlin
fun distinctId(): String
```

Returns the registered distinctId property

#### Return

The current distinct ID.

## Get feature flag

### `PostHog.getFeatureFlag`

```kotlin
fun getFeatureFlag(key: String, defaultValue: Any? = null, sendFeatureFlagEvent: Boolean? = null): Any?
```

Returns the feature flag Docs https://posthog.com/docs/feature-flags and https://posthog.com/docs/experiments

#### Return

The feature flag value, or defaultValue if not found.

#### Parameters

- `key`: the Key
- `defaultValue`: the default value if not found
- `sendFeatureFlagEvent`: (optional) If false, we won't send an $feature\_flag\_called event to PostHog.

## Get feature flag payload

### `PostHog.getFeatureFlagPayload`

```kotlin
fun getFeatureFlagPayload(key: String, defaultValue: Any? = null): Any?
```

#### Deprecated

Use getFeatureFlagResult() instead; note it sends the $feature\_flag\_called event by default.

#### Replace with

```kotlin
getFeatureFlagResult(key)?.payload
```

Returns the feature flag payload Docs https://posthog.com/docs/feature-flags and https://posthog.com/docs/experiments

#### Return

The feature flag payload, or defaultValue if not found.

#### Parameters

- `key`: the Key
- `defaultValue`: the default value if not found

## Get feature flag result

### `PostHog.getFeatureFlagResult`

```kotlin
fun getFeatureFlagResult(key: String, sendFeatureFlagEvent: Boolean? = null): FeatureFlagResult?
```

Returns the feature flag result containing both value and payload. This is the recommended way to access feature flags when you need both the flag value and its payload atomically.

Docs https://posthog.com/docs/feature-flags and https://posthog.com/docs/experiments

#### Return

FeatureFlagResult if the flag exists, null otherwise

#### Parameters

- `key`: The feature flag key
- `sendFeatureFlagEvent`: If false, won't send $feature\_flag\_called event to PostHog

## Get feature flags

### `PostHog.getAllFeatureFlags`

```kotlin
fun getAllFeatureFlags(): List<FeatureFlagResult>?
```

Returns list of all feature flag result containing both value and payload. Docs https://posthog.com/docs/feature-flags and https://posthog.com/docs/experiments

#### Return

The currently loaded feature flag results, or null when flags are unavailable.

## Get session ID

### `PostHog.getSessionId`

```kotlin
fun getSessionId(): UUID?
```

Returns the session Id if a session is active

#### Return

The current session ID, or null if no session is active.

## Group

### `PostHog.group`

```kotlin
fun group(type: String, key: String, groupProperties: Map<String, Any>? = null)
```

Creates a group Docs https://posthog.com/docs/product-analytics/group-analytics

#### Parameters

- `type`: the Group type
- `key`: the Group key
- `groupProperties`: the Group properties, set as a "$group\_set" property, Docs https://posthog.com/docs/product-analytics/group-analytics

## Is feature enabled

### `PostHog.isFeatureEnabled`

```kotlin
fun isFeatureEnabled(key: String, defaultValue: Boolean = false, sendFeatureFlagEvent: Boolean? = null): Boolean
```

Returns if a feature flag is enabled, the feature flag must be a Boolean Docs https://posthog.com/docs/feature-flags and https://posthog.com/docs/experiments

#### Return

Whether the feature flag is enabled.

#### Parameters

- `key`: the Key
- `defaultValue`: the default value if not found, false if not given
- `sendFeatureFlagEvent`: (optional) If false, we won't send an $feature\_flag\_called event to PostHog.

## Is opt out

### `PostHog.isOptOut`

```kotlin
fun isOptOut(): Boolean
```

Checks if the optOut mode is enabled or disabled

## Is session replay active

### `PostHog.isSessionReplayActive`

```kotlin
fun isSessionReplayActive(): Boolean
```

Returns if Session Replay is Active Android only.

#### Return

Whether session replay is currently active.

## Opt in

### `PostHog.optIn`

```kotlin
fun optIn()
```

Enables the SDK to capture events

### `PostHog.optOut`

```kotlin
fun optOut()
```

Disables the SDK to capture events until you optIn again

## Register

### `PostHog.register`

```kotlin
fun register(key: String, value: Any)
```

Register a property to always be sent with all the following events until you call unregister with the same key PostHogPreferences.ALL\_INTERNAL\_KEYS are not allowed since they are internal and used by the SDK only.

#### Parameters

- `key`: the Key
- `value`: the Value

## Reload feature flags

### `PostHog.reloadFeatureFlags`

```kotlin
fun reloadFeatureFlags(onFeatureFlags: PostHogOnFeatureFlags? = null)
```

Reloads the feature flags

#### Parameters

- `onFeatureFlags`: the callback to get notified once feature flags is ready to use

## Reset

### `PostHog.reset`

```kotlin
fun reset()
```

Resets all the cached properties including the distinctId The SDK will behave as its been setup for the first time

## Reset group properties for flags

### `PostHog.resetGroupPropertiesForFlags`

```kotlin
fun resetGroupPropertiesForFlags(type: String? = null, reloadFeatureFlags: Boolean = true)
```

Clears group properties for feature flag evaluation.

#### Parameters

- `type`: Optional group type to clear. If null, clears all group properties.
- `reloadFeatureFlags`: Whether to automatically reload feature flags after resetting properties

## Reset person properties for flags

### `PostHog.resetPersonPropertiesForFlags`

```kotlin
fun resetPersonPropertiesForFlags(reloadFeatureFlags: Boolean = true)
```

Resets all person properties that were set for feature flag evaluation.

#### Parameters

- `reloadFeatureFlags`: Whether to automatically reload feature flags after resetting properties

## Screen

### `PostHog.screen`

```kotlin
fun screen(screenTitle: String, properties: Map<String, Any>? = null)
```

Captures a screen view event

#### Parameters

- `screenTitle`: the screen title. It takes precedence over a `$screen_name` key in properties.
- `properties`: the custom properties

## Set group properties for flags

### `PostHog.setGroupPropertiesForFlags`

```kotlin
fun setGroupPropertiesForFlags(type: String, groupProperties: Map<String, Any>, reloadFeatureFlags: Boolean = true)
```

Sets properties for a specific group type to include when evaluating feature flags.

#### Parameters

- `type`: The group type identifier (e.g., "organization", "team")
- `groupProperties`: Dictionary of properties to set for this group type
- `reloadFeatureFlags`: Whether to automatically reload feature flags after setting properties

## Set person properties

### `PostHog.setPersonProperties`

```kotlin
fun setPersonProperties(userPropertiesToSet: Map<String, Any>? = null, userPropertiesToSetOnce: Map<String, Any>? = null)
```

Sets properties on the person profile associated with the current distinct\_id. Learn more about identifying users

Updates user properties that are stored with the person profile in PostHog. If `personProfiles` is set to `IDENTIFIED_ONLY` and no profile exists, this will create one.

This method sends a `$set` event to PostHog.

#### Parameters

- `userPropertiesToSet`: Optional: Properties to store about the user. These will overwrite existing values.
- `userPropertiesToSetOnce`: Optional: Properties to store about the user only if not previously set. Note: For feature flag evaluations, if the same key is present in both parameters, the value from userPropertiesToSet will take precedence.

## Set person properties for flags

### `PostHog.setPersonPropertiesForFlags`

```kotlin
fun setPersonPropertiesForFlags(userProperties: Map<String, Any>, reloadFeatureFlags: Boolean = true)
```

Sets person properties that will be included in feature flag evaluation requests.

#### Parameters

- `userProperties`: Dictionary of person properties to include in flag evaluation
- `reloadFeatureFlags`: Whether to automatically reload feature flags after setting properties

## Shutdown

### `PostHog.close`

```kotlin
fun close()
```

Closes the SDK

## Start session recording

### `PostHog.startSessionReplay`

```kotlin
fun startSessionReplay(resumeCurrent: Boolean = true)
```

Starts session replay. This method will be NoOp if session replay is disabled in your project settings.

Note: This method respects ingestion controls configured in your project settings. If sampling is configured and the session is not sampled, recording will not start. If event triggers are configured, recording will not start until a matching event is captured.

Android only.

#### Parameters

- `resumeCurrent`: Whether to resume session replay of current session (true) or start a new session (false).

## Stop session recording

### `PostHog.stopSessionReplay`

```kotlin
fun stopSessionReplay()
```

Stops the current session replay if one is in progress.

Android only.

## Unregister

### `PostHog.unregister`

```kotlin
fun unregister(key: String)
```

Unregisters the previously set property to be sent with all the following events

#### Parameters

- `key`: the Key

## Device ID generator

### `PostHog.getDeviceId`

```kotlin
fun getDeviceId(): String
```

Returns the stable device identifier used for device-level feature flag bucketing. This ID persists across identify and reset calls, only changing on a fresh app install, manual cache clearing, or OS-initiated storage cleanup.

#### Return

The device ID, or an empty string if not yet initialized

## Session manager

### `PostHog.endSession`

```kotlin
fun endSession()
```

Ends a session The SDK will automatically end a session when you call close On Android, the SDK will automatically end a session when the app is in the background for at least 30 minutes

### `PostHog.startSession`

```kotlin
fun startSession()
```

Starts a session The SDK will automatically start a session when you call setup On Android, the SDK will automatically start a session when the app is in the foreground

## SDK utils

### `PostHog.captureFeatureInteraction`

```kotlin
fun captureFeatureInteraction(flag: String, flagVariant: String? = null)
```

Captures a feature interaction event when a user interacts with a feature.

#### Parameters

- `flag`: The feature flag key
- `flagVariant`: Optional variant key.

### `PostHog.captureFeatureView`

```kotlin
fun captureFeatureView(flag: String, flagVariant: String? = null)
```

Captures a feature view event when a user sees a feature in the UI.

#### Parameters

- `flag`: The feature flag key.
- `flagVariant`: Optional variant key.

### `PostHog.capturePushNotificationOpened`

```kotlin
fun capturePushNotificationOpened(title: String? = null, body: String? = null, payload: Map<String, Any?>? = null, action: String? = null)
```

Captures a `$push_notification_opened` event when the user opens a push notification.

On Android, cold-start taps on a tray notification are captured automatically (from the launch intent) when `capturePushNotificationOpened` is enabled. Call this manually for the paths auto-detection can't observe — foreground data messages and warm-start `onNewIntent`.

Each key of `payload["posthog"]` (accepted as a `Map` or a JSON string) is attached as a `$notification_<key>` property.

A notification sent by PostHog is captured once: when `payload["posthog"]` carries an `invocation_id`, a repeat with the same `invocation_id` and `action_id` within 5 minutes of the first capture is skipped, whether that first capture came from this method or from the SDK's automatic capture. Payloads without a `posthog.invocation_id` are always captured. Only the 20 most recently captured notifications are remembered, so a host that reports more than that inside the window can capture a repeat of the oldest.

A rerun of that workflow, or a loop back to its push step, sends the pair again as a new notification, and its open counts separately: a payload whose `google.message_id` differs from the one captured first is captured. Forward the tapped intent's extras (which carry that id) to keep those apart — a payload without one, such as an FCM foreground `message.data`, is treated as a repeat report of the tap already captured.

#### Parameters

- `title`: the notification title, attached as `$notification_title` when non-empty
- `body`: the notification body, attached as `$notification_body` when non-empty
- `payload`: the notification data payload; its `posthog` entry is spread into `$notification_*` props
- `action`: the tapped notification action identifier, attached as `$notification_action`; leave `null` for a plain tap/open (the default action carries no identifier)

### `PostHog.isSessionActive`

```kotlin
fun isSessionActive(): Boolean
```

Returns if a session is active

#### Return

Whether a session is currently active.

### `PostHog.registerPushNotificationToken`

```kotlin
fun registerPushNotificationToken(deviceToken: String, appId: String)
```

Registers a push notification device token with PostHog so the backend can deliver push notifications sent from PostHog Workflows to this device.

The token is persisted and (re)sent with the current distinctId; transient failures are retried automatically. On Android, when Firebase Messaging is on the classpath and `capturePushNotificationSubscriptions` is enabled, this is called for you at startup. Call it manually to forward Firebase's `onNewToken` or `onRegistered` callback. An explicit call refreshes a previously delivered subscription, even when the identifier is unchanged, so a subscription removed after a delivery failure can be restored. Pending registrations retain their retry backoff.

#### Parameters

- `deviceToken`: the device push token or Firebase Installation ID (FID)
- `appId`: the app identifier — the Firebase `project_id` on Android, the APNS `bundle_id` on iOS

### `PostHog.unregisterPushNotificationToken`

```kotlin
fun unregisterPushNotificationToken()
```

Unregisters this device's push token from PostHog so Workflows stop targeting it — for example from your logout flow.

Sends a `DELETE /api/push_subscriptions` for the current distinctId (the backend unsets the subscription property) and forgets the locally stored token. The DELETE intent is durable: an offline or transiently failed attempt is persisted and retried passively on the next flush or app launch, the same way registration is. On reset the SDK already moves any registered token to the new anonymous identity (unregister then re-register), independently of `capturePushNotificationSubscriptions` — that flag only gates automatic token subscription at startup. Call it directly if you manage push subscriptions yourself.

### `postHogAutocaptureNoCapture`

```kotlin
fun Modifier.postHogAutocaptureNoCapture(isEnabled: Boolean = true): Modifier
```

Excludes this element and its descendants from automatic interaction events. Changes to isEnabled apply to subsequent interactions. Does not change session replay masking.

#### Parameters

- `isEnabled`: Whether to exclude the subtree. Defaults to true; false has no effect.

## PostHogAndroid

### `PostHogAndroid.capturePushNotificationOpened`

```kotlin
fun capturePushNotificationOpened(intent: Intent?)
```

Captures `$push_notification_opened` for a notification tap carried on intent.

The SDK reads the tray intent when the launch Activity is created. A host that configures PostHog from its own runtime — Flutter and React Native reach `setup()` from Dart/JS, after the launch Activity has already created, started and resumed — installs too late to see that callback, and should pass the Activity's intent here instead.

Deduped by `google.message_id`, so calling it alongside the automatic path cannot double-count, including across a process-death restore: on this path recently opened ids are remembered on disk, because a caller with no `savedInstanceState` cannot otherwise tell a restore — which hands the Activity back its original intent — from a real second tap. The cost of that trade is that a genuine second tap of the *same* notification after a process restart reads as a restore and is dropped, until that id ages out of the remembered set.

No-op when intent is null or carries no push id, when `capturePushNotificationOpened` is disabled, or before setup. Events go to the shared instance, so a host that only called with is not served.

Covers launch intents only. A warm-start tap arrives through `Activity.onNewIntent`, which nothing here observes — pass that intent in yourself.

## Types

### `FeatureFlagResult`

```kotlin
class FeatureFlagResult(val key: String, val enabled: Boolean, val variant: String?, val payload: Any?)
```

The result of evaluating a feature flag.

#### Properties

- `val enabled: Boolean`: whether the feature flag is enabled
- `val key: String`: the key of the feature flag
- `val payload: Any?`: the payload associated with the feature flag (if any)
- `val value: Any`: Returns the effective value of the feature flag. For multivariate flags, returns the variant string. For boolean flags, returns the enabled boolean.
- `val variant: String?`: the variant of the feature flag (null for boolean flags)

#### Functions

- `inline fun <T> getPayloadAs(): T?`: Returns the payload deserialized as the specified type.

### `LinkSurveyQuestion`

```kotlin
data class LinkSurveyQuestion(val link: String?) : SurveyQuestion
```

#### Properties

- `val branching: SurveyQuestionBranching? = null`
- `val buttonText: String? = null`
- `val description: String? = null`
- `val descriptionContentType: SurveyTextContentType? = null`
- `val id: String? = null`
- `val link: String?`
- `val optional: Boolean? = null`
- `val question: String? = null`
- `val skipSubmitButton: Boolean? = null`
- `val translations: Map<String, SurveyQuestionTranslation>? = null`
- `val type: SurveyQuestionType? = null`

### `MultipleSurveyQuestion`

```kotlin
data class MultipleSurveyQuestion(val choices: List<String>?, val hasOpenChoice: Boolean?, val shuffleOptions: Boolean?) : SurveyQuestion
```

#### Properties

- `val branching: SurveyQuestionBranching? = null`
- `val buttonText: String? = null`
- `val choices: List<String>?`
- `val description: String? = null`
- `val descriptionContentType: SurveyTextContentType? = null`
- `val hasOpenChoice: Boolean?`
- `val id: String? = null`
- `val optional: Boolean? = null`
- `val question: String? = null`
- `val shuffleOptions: Boolean?`
- `val skipSubmitButton: Boolean? = null`
- `val translations: Map<String, SurveyQuestionTranslation>? = null`
- `val type: SurveyQuestionType? = null`

### `OnPostHogSurveyClosed`

```kotlin
typealias OnPostHogSurveyClosed = (survey: PostHogDisplaySurvey) -> Unit
```

To be called when a survey is dismissed

#### Parameters

- `survey`: The survey that was closed

### `OnPostHogSurveyResponse`

```kotlin
typealias OnPostHogSurveyResponse = (survey: PostHogDisplaySurvey, index: Int, response: PostHogSurveyResponse) -> PostHogNextSurveyQuestion?
```

To be called when a user responds to a survey question

#### Return

The next question state (next question index and completion flag)

#### Parameters

- `survey`: The current survey being displayed
- `index`: The index of the question being answered
- `response`: The user's response to the question

### `OnPostHogSurveyShown`

```kotlin
typealias OnPostHogSurveyShown = (survey: PostHogDisplaySurvey) -> Unit
```

To be called when a survey is successfully shown to the user

#### Parameters

- `survey`: The survey that was displayed

### `OpenSurveyQuestion`

```kotlin
class OpenSurveyQuestion : SurveyQuestion
```

#### Properties

- `val branching: SurveyQuestionBranching? = null`
- `val buttonText: String? = null`
- `val description: String? = null`
- `val descriptionContentType: SurveyTextContentType? = null`
- `val id: String? = null`
- `val optional: Boolean? = null`
- `val question: String? = null`
- `val skipSubmitButton: Boolean? = null`
- `val translations: Map<String, SurveyQuestionTranslation>? = null`
- `val type: SurveyQuestionType? = null`

### `PersonProfiles`

```kotlin
enum PersonProfiles : Enum<PersonProfiles>
```

Controls when captured events should create or update PostHog person profiles.

#### Entries

- `NEVER`: Never process person profile data. Anonymous users are not merged when they identify.
- `ALWAYS`: Process person profile data for all captured events.
- `IDENTIFIED_ONLY`: Process person profile data only after `identify`, `alias`, or `group` calls.

### `PostHogAndroidConfig`

```kotlin
class PostHogAndroidConfig @JvmOverloads constructor(projectToken: String, host: String = DEFAULT_HOST, var captureApplicationLifecycleEvents: Boolean = true, var captureDeepLinks: Boolean = true, var captureScreenViews: Boolean = true, var sessionReplayConfig: PostHogSessionReplayConfig = PostHogSessionReplayConfig(), var capturePushNotificationSubscriptions: Boolean = true, var capturePushNotificationOpened: Boolean = true) : PostHogConfig
```

Android SDK configuration.

#### Parameters

- `projectToken`: Your PostHog project token, which starts with `phc_`. Required, no default. Find it in your PostHog project settings: https://us.posthog.com/settings/project-details#variables. Leading and trailing whitespace is trimmed. Formerly named `apiKey`. Example: `PostHogAndroidConfig(projectToken = "phc_your_project_token")`.
- `host`: PostHog ingestion host. Defaults to DEFAULT\_HOST.

#### Properties

- `var captureApplicationLifecycleEvents: Boolean`: Whether to capture application lifecycle events automatically, including app installed, app updated, app opened, and app backgrounded.
- `var captureDeadClicks: Boolean`: Captures `$dead_click` when a supported target has no observed meaningful UI response for three seconds. Default: false. Bounded local View/Compose change detection may transiently process unmasked UI text; raw text is not retained and neither text nor its ephemeral digest is sent. Unsupported/incomplete observations are skipped. Independent of semantic capture and replay; shares interaction exclusions.
- `var captureDeepLinks: Boolean`: Whether to capture `Deep Link Opened` events automatically. For warm links delivered to `singleTop` or `singleTask` activities, call `setIntent(intent)` in `Activity.onNewIntent` after `super.onNewIntent(intent)`. The SDK checks the current intent on resume and captures each new Intent instance, even if its URL is unchanged. Ordinary resumes do not recapture the same intent. Creation capture, including activity recreation, is unchanged. Default: `true`.
- `var captureElementInteractions: Boolean`: Captures `$autocapture` for actionable View and Compose taps. Default: false. Configure before setup. Independent of session replay; sends types and resource IDs/test tags and window-local touch coordinates in dp, never text, content descriptions or input values. Use only non-sensitive test tags. Exclude a View subtree with `com.posthog.android.R.id.posthog_autocapture_no_capture` set to true, or use postHogAutocaptureNoCapture for Compose.
- `var capturePushNotificationOpened: Boolean`: Whether to auto-capture `$push_notification_opened` for cold-start taps on a tray notification (detected via the launch intent's `google.message_id` extra). A warm-start tap arrives at `Activity.onNewIntent`, which is not observable here — forward that intent to PostHogAndroid.capturePushNotificationOpened, which is deduped against this path. Foreground data messages and push delivered outside FCM need PostHog.capturePushNotificationOpened, which dedupes only notifications sent by PostHog. Also gates PostHogAndroid.capturePushNotificationOpened. Default: `true`.
- `var capturePushNotificationSubscriptions: Boolean`: Whether to auto-register this device's push token at startup. When enabled and Firebase Messaging is on the classpath, the SDK fetches the FCM token + Firebase `project_id` and registers them so PostHog Workflows can deliver push notifications. When the manifest enables `firebase_messaging_installation_id_enabled`, the Firebase Installation ID is registered instead. No effect when Firebase is absent (a single debug log). Instant token refresh still needs the app to forward `onNewToken` (or, in installation ID mode, `onRegistered`) to `registerPushNotificationToken(...)`. Default: `true`.
- `var captureRageClicks: Boolean`: Captures `$rageclick` after four nearby taps on the same target within one second. Default: false. Independent of semantic capture and replay; shares their interaction exclusions and privacy-safe properties.
- `var captureScreenViews: Boolean`: Whether to capture a `$screen` event whenever a foreground Activity starts (via `ActivityLifecycleCallbacks.onActivityStarted`). When enabled, the most recent screen name is also attached as `$screen_name` to every subsequent event captured by the SDK. To opt out of `$screen_name` stamping entirely, set this to `false` and avoid calling `PostHog.screen(...)` manually. Default: `true`.
- `var sessionReplayConfig: PostHogSessionReplayConfig`: Android-specific session replay capture options.

### `PostHogBeforeSend`

```kotlin
fun interface PostHogBeforeSend
```

Hook invoked before an analytics event is queued.

Return the same or a modified PostHogEvent to continue, or `null` to drop the event.

#### Functions

- `fun run(event: PostHogEvent): PostHogEvent?`: Runs the hook for event.

### `PostHogBeforeSendLog`

```kotlin
fun interface PostHogBeforeSendLog
```

Hook that runs on every captured log record before it is enqueued.

Return `null` to drop the record, return the (possibly mutated) record to keep it. Multiple hooks compose left-to-right; if any returns `null`, later hooks are skipped. Registered via PostHogLogsConfig.addBeforeSend.

Runs synchronously on the thread that called the logger — keep work fast (no network, no disk).

#### Kotlin (SAM)

```kotlin
config.logs.addBeforeSend { record ->
    if (record.level == PostHogLogSeverity.TRACE) null else record
}
```

#### Java

```
config.getLogs().addBeforeSend(record -> {
    if (record.getLevel() == PostHogLogSeverity.TRACE) return null;
    return record;
});
```

#### See also

- `PostHogLogsConfig.addBeforeSend`:
- `PostHogLogsConfig.removeBeforeSend`:

#### Functions

- `fun run(record: PostHogLogRecord): PostHogLogRecord?`: Called once per captured record. Return the record (possibly with a mutated copy via PostHogLogRecord.copy) to keep it, or `null` to drop it.

### `PostHogBootstrapConfig`

```kotlin
class PostHogBootstrapConfig @JvmOverloads constructor(val distinctId: String? = null, val isIdentifiedId: Boolean = false, val featureFlags: Map<String, Any>? = null, val featureFlagPayloads: Map<String, Any?>? = null)
```

Pre-seeded identity and feature-flag state applied before any network request completes: identity only on the very first SDK launch, and the feature-flag base layer on every launch until `reset()`.

Set PostHogConfig.bootstrap before calling setup to seed the distinct id and feature flag state. Events captured synchronously during initialization then carry a caller-controlled `$distinct_id` rather than the SDK-generated UUID, and feature-flag reads return caller-provided values before the first `/flags` response. Mirrors the bootstrap option in posthog-js.

Bootstrap identity applies to the very first session. An anonymous bootstrap (isIdentifiedId false) seeds the anonymous id only when none is persisted, and is otherwise ignored. An identified bootstrap (isIdentifiedId true) seeds the distinct id on a fresh install; if a local anonymous user already exists it is merged into the identified id via `identify()`, while a different already-identified user is preserved and a warning is logged. Bootstrapped feature flags are an initial snapshot, not a permanent base: they win over any persisted flag cache at setup and are served until the first `/flags` response, which then replaces them entirely — a complete response drops bootstrapped-only keys, while only a partial or errored response preserves the keys it did not recompute. `reset()` drops the snapshot.

#### Properties

- `val distinctId: String? = null`: The distinct id to seed on first launch.
- `val featureFlagPayloads: Map<String, Any?>? = null`: JSON payloads paired with featureFlags, keyed by flag key. Each value is the already-decoded payload (map, list, string, number, ...).
- `val featureFlags: Map<String, Any>? = null`: Feature flag values served until the first `/flags` response arrives, keyed by flag key. Each value is a Boolean for boolean flags or a String for multivariate flags. Only enabled values are served — `true` or a non-empty String; a `false` or empty-string entry is treated as not bootstrapped (matching posthog-js's `!!value` check) and dropped, indistinguishable from a key that was never provided. Values must be JSON-serializable; any non-serializable entry (e.g. NaN) is dropped with a logged warning rather than surfaced.
- `val isIdentifiedId: Boolean = false`: Whether distinctId represents an already-identified user.

### `PostHogCompression`

```kotlin
enum PostHogCompression : Enum<PostHogCompression>
```

Controls whether request bodies are compressed before being sent to the PostHog API.

#### Entries

- `GZIP`: Gzip request bodies.
- `NONE`: Send request bodies uncompressed.

### `PostHogConfig`

```kotlin
class PostHogConfig(projectToken: String, host: String = DEFAULT_HOST, var debug: Boolean = false, var optOut: Boolean = false, var sendFeatureFlagEvent: Boolean = true, var featureFlagCalledCacheSize: Int = DEFAULT_FEATURE_FLAG_CALLED_CACHE_SIZE, var preloadFeatureFlags: Boolean = true, var evaluationContexts: List<String>? = null, var setDefaultPersonProperties: Boolean = true, var remoteConfig: Boolean = true, var flushAt: Int = DEFAULT_FLUSH_AT, var maxQueueSize: Int = DEFAULT_MAX_QUEUE_SIZE, var maxBatchSize: Int = DEFAULT_MAX_BATCH_SIZE, var maxRetries: Int = 3, var featureFlagRequestMaxRetries: Int = 1, var flushIntervalSeconds: Int = DEFAULT_FLUSH_INTERVAL_SECONDS, var encryption: PostHogEncryption? = null, var onFeatureFlags: PostHogOnFeatureFlags? = null, var sessionReplay: Boolean = false, var propertiesSanitizer: PostHogPropertiesSanitizer? = null, var getAnonymousId: (UUID) -> UUID = { it }, var reuseAnonymousId: Boolean = false, var personProfiles: PersonProfiles = PersonProfiles.IDENTIFIED_ONLY, var surveys: Boolean = false, var proxy: Proxy? = null, var surveysConfig: PostHogSurveysConfig = PostHogSurveysConfig(), var logs: PostHogLogsConfig = PostHogLogsConfig(), val remoteConfigProvider: (PostHogConfig, PostHogApi, ExecutorService, PostHogDefaultPersonPropertiesProvider?, PostHogOnRemoteConfigLoaded?, PostHogFeatureFlagCalledProvider?) -> PostHogFeatureFlagsInterface = DEFAULT_REMOTE_CONFIG_PROVIDER, val queueProvider: (PostHogConfig, PostHogApi, PostHogApiEndpoint, String?, ExecutorService) -> PostHogQueueInterface<PostHogEvent> = DEFAULT_QUEUE_PROVIDER, val errorTrackingConfig: PostHogErrorTrackingConfig = PostHogErrorTrackingConfig(), var releaseIdentifier: String? = null, var bootstrap: PostHogBootstrapConfig? = null, var pushIdentityProvider: (distinctId: String, appId: String, completion: (String?) -> Unit) -> Unit? = null)
```

The SDK Config

#### Properties

- `val apiKey: String`: Deprecated alias for projectToken; returns the same trimmed value.
- `val beforeSendList: List<PostHogBeforeSend>`: The beforeSend list
- `var bootstrap: PostHogBootstrapConfig?`: Optional bootstrap configuration that seeds identity and feature-flag state before any network request completes. Identity is seeded only on the very first launch, when nothing has been persisted yet. The feature-flag snapshot is applied on every launch as the initial layer — served immediately and taking precedence over any persisted flag cache — until the first `/flags` response replaces it (a complete response drops bootstrapped-only keys; a partial or errored one keeps the keys it did not recompute) or `reset()` drops it. See PostHogBootstrapConfig for the precedence rules.
- `var compression: PostHogCompression`: How the SDK compresses request bodies before sending them to the PostHog API.
- `var debug: Boolean`: Logs the debug logs to the logger if enabled Defaults to false
- `var encryption: PostHogEncryption?`: Hook for encrypt and decrypt events Devices are sandbox so likely not needed Defaults to no encryption
- `val errorTrackingConfig: PostHogErrorTrackingConfig`: Configuration for PostHog Error Tracking feature.
- `var evaluationContexts: List<String>?`: Optional list of evaluation context tags for feature flag evaluation When specified, only feature flags that have at least one matching evaluation tag will be evaluated Feature flags with no evaluation tags will always be evaluated for backward compatibility Example: listOf("production", "web", "checkout") Defaults to null (evaluate all flags)
- `var evaluationEnvironments: List<String>?`: Optional list of evaluation environment tags for feature flag evaluation
- `var featureFlagCalledCacheSize: Int`: Maximum number of distinct feature flag calls to cache for deduplication When the cache is full, the least recently used entries are evicted Defaults to 1000
- `var featureFlagRequestMaxRetries: Int`: Maximum number of retries for feature flag requests after transient network errors. Defaults to 1. Set to 0 to disable feature flag request retries.
- `var flushAt: Int`: Number of minimum events before they are sent over the wire Defaults to 20
- `var flushIntervalSeconds: Int`: Interval in seconds for sending events over the wire The lower the number, most likely more battery is used Defaults to 30s
- `var getAnonymousId: (UUID) -> UUID`: Hook that allows for modification of the default mechanism for generating anonymous id (which as of now is just random UUID v7)
- `val host: String`: The PostHog Host Defaults to https://us.i.posthog.com EU Host: https://eu.i.posthog.com
- `val integrations: List<PostHogIntegration>`: The integrations list
- `var logs: PostHogLogsConfig`: Configuration for the logs subsystem. See PostHogLogsConfig for per-field mutability semantics — resource attributes and the `flushAt` / `maxBatchSize` knobs are snapshotted at setup; the `beforeSend` chain, `maxBufferSize`, and `flushIntervalSeconds` remain live.
- `var maxBatchSize: Int`: Number of maximum events in a batch call Defaults to 50
- `var maxQueueSize: Int`: Number of maximum events in memory and disk, when the maximum is exceed, the oldest event is deleted and the new one takes place Defaults to 1000
- `var maxRetries: Int`: Maximum number of retries for push subscription registration failures.
- `var onFeatureFlags: PostHogOnFeatureFlags?`: Hook that is called when feature flags are loaded Defaults to no callback
- `@Volatilevar optOut: Boolean`: This flag prevents capturing any data if enabled You can overwrite this value at runtime by calling PostHog.optIn or PostHog.optOut Defaults to false
- `var personProfiles: PersonProfiles`: Determines the behavior for processing user profiles.
- `var preloadFeatureFlags: Boolean`: Preload feature flags automatically Docs https://posthog.com/docs/feature-flags and https://posthog.com/docs/experiments Defaults to true
- `val projectToken: String`: Your PostHog project token, trimmed of leading and trailing whitespace.
- `var propertiesSanitizer: PostHogPropertiesSanitizer?`: Hook that allows to sanitize the event properties The hook is called before the event is cached or sent over the wire
- `var proxy: Proxy?`: Configures an optional HTTP proxy for the PostHog API client.
- `var pushIdentityProvider: (distinctId: String, appId: String, completion: (String?) -> Unit) -> Unit?`: Hook that supplies a signed identity token for push subscription requests, enabling the optional identity verification of the push subscriptions API.
- `var releaseIdentifier: String?`: The release identifier used to identify the correct proguard mappings (mapping.txt) in order to make minified stack traces into human readable stack traces
- `var remoteConfig: Boolean`: Preload PostHog remote config automatically Defaults to true
- `val remoteConfigProvider: (PostHogConfig, PostHogApi, ExecutorService, PostHogDefaultPersonPropertiesProvider?, PostHogOnRemoteConfigLoaded?, PostHogFeatureFlagCalledProvider?) -> PostHogFeatureFlagsInterface`: Factory to instantiate a custom com.posthog.internal.PostHogRemoteConfigInterface implementation.
- `@Volatilevar requestHeaders: Map<String, String>`: Custom headers to send with every request to the PostHog API.
- `var reuseAnonymousId: Boolean`: Flag to reuse the anonymous id. If enabled, the anonymous id will be reused across `identify()` and `reset()` calls.
- `var sendFeatureFlagEvent: Boolean`: Send a $feature\_flag\_called event when a feature flag is used automatically Used by experiments
- `var sessionReplay: Boolean`: Enable Recording of Session Replays for Android Requires Record user sessions to be enabled in the PostHog Project Settings Defaults to false
- `var setDefaultPersonProperties: Boolean`: Automatically set common device and app properties as person properties for feature flag evaluation.
- `var surveys: Boolean`: Enable Surveys for Android (Intended for internal use only)
- `var surveysConfig: PostHogSurveysConfig`: Configuration for PostHog Surveys feature. (Intended for internal use only)
- `var tracingHeaders: List<String>?`: When set, PostHog injects tracing headers into OkHttp requests whose destination hostname exactly matches one of the configured hostnames.

#### Functions

- `fun addBeforeSend(beforeSend: PostHogBeforeSend)`: Adds a new PostHogBeforeSend
- `fun addIntegration(integration: PostHogIntegration)`: Adds a new integration
- `fun removeBeforeSend(beforeSend: PostHogBeforeSend)`: Removes the PostHogBeforeSend
- `fun removeIntegration(integration: PostHogIntegration)`: Removes the integration

### `PostHogDisplayChoiceQuestion`

```kotlin
class PostHogDisplayChoiceQuestion @JvmOverloads constructor(val id: String, val question: String, val questionDescription: String?, val questionDescriptionContentType: PostHogDisplaySurveyTextContentType, val isOptional: Boolean, val buttonText: String?, val choices: List<String>, val hasOpenChoice: Boolean, val shuffleOptions: Boolean, val isMultipleChoice: Boolean, val skipSubmitButton: Boolean = false) : PostHogDisplaySurveyQuestion
```

Represents a question where users can select one or more choices

#### Properties

- `val buttonText: String?`: Optional custom text for the question's action button
- `val choices: List<String>`: The list of options for the user to choose from
- `val hasOpenChoice: Boolean`: Whether the question includes an "other" option for users to input free-form text
- `val id: String`: The main question id
- `val isMultipleChoice: Boolean`: Whether the user can select multiple options
- `val isOptional: Boolean`: Whether the question can be skipped
- `val question: String`: The main question text to display
- `val questionDescription: String?`: Optional additional description or context for the question
- `val questionDescriptionContentType: PostHogDisplaySurveyTextContentType`: Content type for the question description (HTML or plain text)
- `val shuffleOptions: Boolean`: Whether the options should be shuffled when presented
- `val skipSubmitButton: Boolean = false`: Whether to auto-submit single choices without an open-ended option

### `PostHogDisplayLinkQuestion`

```kotlin
class PostHogDisplayLinkQuestion(val id: String, val question: String, val questionDescription: String?, val questionDescriptionContentType: PostHogDisplaySurveyTextContentType, val isOptional: Boolean, val buttonText: String?, val link: String?) : PostHogDisplaySurveyQuestion
```

Represents a question with a clickable link

#### Properties

- `val buttonText: String?`: Optional custom text for the question's action button
- `val id: String`: The main question id
- `val isOptional: Boolean`: Whether the question can be skipped
- `val link: String?`: The URL that will be opened when the link is clicked
- `val question: String`: The main question text to display
- `val questionDescription: String?`: Optional additional description or context for the question
- `val questionDescriptionContentType: PostHogDisplaySurveyTextContentType`: Content type for the question description (HTML or plain text)

### `PostHogDisplayOpenQuestion`

```kotlin
class PostHogDisplayOpenQuestion(val id: String, val question: String, val questionDescription: String?, val questionDescriptionContentType: PostHogDisplaySurveyTextContentType, val isOptional: Boolean, val buttonText: String?) : PostHogDisplaySurveyQuestion
```

Represents an open-ended question where users can input free-form text

#### Properties

- `val buttonText: String?`: Optional custom text for the question's action button
- `val id: String`: The main question id
- `val isOptional: Boolean`: Whether the question can be skipped
- `val question: String`: The main question text to display
- `val questionDescription: String?`: Optional additional description or context for the question
- `val questionDescriptionContentType: PostHogDisplaySurveyTextContentType`: Content type for the question description (HTML or plain text)

### `PostHogDisplayRatingQuestion`

```kotlin
class PostHogDisplayRatingQuestion @JvmOverloads constructor(val id: String, val question: String, val questionDescription: String?, val questionDescriptionContentType: PostHogDisplaySurveyTextContentType, val isOptional: Boolean, val buttonText: String?, val ratingType: PostHogDisplaySurveyRatingType, val scaleLowerBound: Int, val scaleUpperBound: Int, val lowerBoundLabel: String, val upperBoundLabel: String, val skipSubmitButton: Boolean = false) : PostHogDisplaySurveyQuestion
```

Represents a rating question where users can select a rating from a scale

#### Properties

- `val buttonText: String?`: Optional custom text for the question's action button
- `val id: String`: The main question id
- `val isOptional: Boolean`: Whether the question can be skipped
- `val lowerBoundLabel: String`: The label for the lower bound of the rating scale
- `val question: String`: The main question text to display
- `val questionDescription: String?`: Optional additional description or context for the question
- `val questionDescriptionContentType: PostHogDisplaySurveyTextContentType`: Content type for the question description (HTML or plain text)
- `val ratingType: PostHogDisplaySurveyRatingType`: The type of rating scale (numbers, emoji)
- `val scaleLowerBound: Int`: The lower bound of the rating scale
- `val scaleUpperBound: Int`: The upper bound of the rating scale
- `val skipSubmitButton: Boolean = false`: Whether selecting a rating submits without an extra submit tap
- `val upperBoundLabel: String`: The label for the upper bound of the rating scale

### `PostHogDisplaySurvey`

```kotlin
data class PostHogDisplaySurvey(val id: String, val name: String, val questions: List<PostHogDisplaySurveyQuestion>, val appearance: PostHogDisplaySurveyAppearance? = null, val startDate: Date? = null, val endDate: Date? = null, val initialQuestionIndex: Int = 0)
```

Represents a survey that is ready to be displayed to the user. Contains all the necessary information for rendering the survey UI.

#### Properties

- `val appearance: PostHogDisplaySurveyAppearance? = null`: The appearance configuration for the survey
- `val endDate: Date? = null`: Optional date indicating when the survey should stop being shown
- `val id: String`: The unique identifier of the survey
- `val initialQuestionIndex: Int = 0`: The question to show when restoring unfinished progress; zero for a new survey.
- `val questions: List<PostHogDisplaySurveyQuestion>`: The list of questions in the survey
- `val startDate: Date? = null`: Optional date indicating when the survey should start being shown

### `PostHogDisplaySurveyAppearance`

```kotlin
data class PostHogDisplaySurveyAppearance(val fontFamily: String? = null, val backgroundColor: String? = null, val borderColor: String? = null, val submitButtonColor: String? = null, val submitButtonText: String? = null, val submitButtonTextColor: String? = null, val textColor: String? = null, val descriptionTextColor: String? = null, val ratingButtonColor: String? = null, val ratingButtonActiveColor: String? = null, val inputBackground: String? = null, val inputTextColor: String? = null, val placeholder: String? = null, val surveyPopupDelaySeconds: Double? = null, val displayThankYouMessage: Boolean = false, val thankYouMessageHeader: String? = null, val thankYouMessageDescription: String? = null, val thankYouMessageDescriptionContentType: PostHogDisplaySurveyTextContentType? = null, val thankYouMessageCloseButtonText: String? = null, val displayIntroScreen: Boolean = false, val introScreenHeader: String? = null, val introScreenDescription: String? = null, val introScreenDescriptionContentType: PostHogDisplaySurveyTextContentType? = null, val introScreenButtonText: String? = null)
```

Represents the appearance configuration for a survey.

#### Properties

- `val backgroundColor: String? = null`: Optional background color as web color (e.g. "#FFFFFF" or "white")
- `val borderColor: String? = null`: Optional border color as web color
- `val descriptionTextColor: String? = null`: Optional color for description text as web color
- `val displayIntroScreen: Boolean = false`: Whether to show an intro screen before the first question
- `val displayThankYouMessage: Boolean = false`: Whether to show a thank you message after survey completion
- `val fontFamily: String? = null`: Optional font family to use throughout the survey
- `val inputBackground: String? = null`: Optional background color for input fields as web color
- `val inputTextColor: String? = null`: Optional text color for input fields as web color
- `val introScreenButtonText: String? = null`: Optional custom text for the button that advances past the intro screen
- `val introScreenDescription: String? = null`: Optional description text for the intro screen
- `val introScreenDescriptionContentType: PostHogDisplaySurveyTextContentType? = null`: Optional content type for the intro screen description
- `val introScreenHeader: String? = null`: Optional header text for the intro screen
- `val placeholder: String? = null`: Optional placeholder text for input fields
- `val ratingButtonActiveColor: String? = null`: Optional color for active/selected rating buttons as web color
- `val ratingButtonColor: String? = null`: Optional color for rating buttons as web color
- `val submitButtonColor: String? = null`: Optional background color for the submit button as web color
- `val submitButtonText: String? = null`: Optional custom text for the submit button
- `val submitButtonTextColor: String? = null`: Optional text color for the submit button as web color
- `val surveyPopupDelaySeconds: Double? = null`: Optional delay, in seconds, before the survey is shown after it is triggered
- `val textColor: String? = null`: Optional primary text color as web color
- `val thankYouMessageCloseButtonText: String? = null`: Optional custom text for the thank you message close button
- `val thankYouMessageDescription: String? = null`: Optional description text for the thank you message
- `val thankYouMessageDescriptionContentType: PostHogDisplaySurveyTextContentType? = null`: Optional content type for the thank you message description
- `val thankYouMessageHeader: String? = null`: Optional header text for the thank you message

### `PostHogDisplaySurveyQuestion`

```kotlin
class PostHogDisplaySurveyQuestion(val id: String, val question: String, val questionDescription: String?, val questionDescriptionContentType: PostHogDisplaySurveyTextContentType, val isOptional: Boolean, val buttonText: String?)
```

Base class for all survey question types

#### Inheritors

- `PostHogDisplayOpenQuestion`:
- `PostHogDisplayLinkQuestion`:
- `PostHogDisplayRatingQuestion`:
- `PostHogDisplayChoiceQuestion`:

#### Properties

- `val buttonText: String?`: Optional custom text for the question's action button
- `val id: String`: The main question id
- `val isOptional: Boolean`: Whether the question can be skipped
- `val question: String`: The main question text to display
- `val questionDescription: String?`: Optional additional description or context for the question
- `val questionDescriptionContentType: PostHogDisplaySurveyTextContentType`: Content type for the question description (HTML or plain text)

### `PostHogDisplaySurveyRatingType`

```kotlin
enum PostHogDisplaySurveyRatingType : Enum<PostHogDisplaySurveyRatingType>
```

Type of rating display for survey rating questions

#### Properties

- `val value: Int`

#### Entries

- `NUMBER`: Display numeric rating options
- `EMOJI`: Display emoji rating options

### `PostHogDisplaySurveyTextContentType`

```kotlin
enum PostHogDisplaySurveyTextContentType : Enum<PostHogDisplaySurveyTextContentType>
```

Content type for text-based survey elements (e.g Question description, Thank you message description)

#### Properties

- `val value: Int`

#### Entries

- `HTML`: Content should be rendered as HTML
- `TEXT`: Content should be rendered as plain text

### `PostHogEncryption`

```kotlin
interface PostHogEncryption
```

Interface for encrypt and decrypt events, by default there's no encrypt since Android is sandboxed https://source.android.com/docs/security/app-sandbox

#### Functions

- `fun decrypt(inputStream: InputStream): InputStream`: Decrypts data read from inputStream.
- `fun encrypt(outputStream: OutputStream): OutputStream`: Encrypts data written to outputStream.

### `PostHogErrorTrackingAutoCaptureIntegration`

```kotlin
class PostHogErrorTrackingAutoCaptureIntegration : PostHogIntegration, Thread.UncaughtExceptionHandler
```

#### Functions

- `@Synchronizedopen override fun install(postHog: PostHogInterface)`: Install the Integration after the SDK is setup that requires a posthog instance to capture events
- `fun onChange()`: Invalidates pending observations when identity, consent, screen, session or close state changes. Called synchronously on the caller's thread; calls may be nested or concurrent. This is a one-shot notification, not a pause/resume boundary. Uninstall must independently clean up.
- `fun onRemoteConfig(loaded: Boolean)`: Called when the remote config attempt for the current identity resolves. Each integration is responsible for enabling or disabling features based on the state of the remote config.
- `fun uncaughtException(thread: Thread, throwable: Throwable)`
- `@Synchronizedopen override fun uninstall()`: Uninstall the Integration after the SDK is closed

### `PostHogErrorTrackingConfig`

```kotlin
class PostHogErrorTrackingConfig @JvmOverloads constructor(var autoCapture: Boolean = false, val inAppIncludes: MutableList<String> = mutableListOf(), val exceptionSteps: PostHogExceptionStepsConfig = PostHogExceptionStepsConfig(), val ignoredExceptionTypes: MutableList<Class<out Throwable>> = mutableListOf())
```

Configuration for PostHog error tracking.

#### Properties

- `var autoCapture: Boolean`: Enable autocapture of exceptions This feature installs an uncaught exception handler (Thread.UncaughtExceptionHandler) that will capture exceptions
- `var captureNativeCrashes: Boolean`: Capture native (NDK) crashes from previous runs of the app.
- `val exceptionSteps: PostHogExceptionStepsConfig`: Configuration for exception steps (breadcrumb-style context records attached to every captured `$exception` event as `$exception_steps`).
- `val ignoredExceptionTypes: MutableList<Class<out Throwable>>`: Throwable classes to drop during capture. The throwable and every cause in its chain are matched via Class.isInstance. Also applies to `$exception` events sent through the generic capture path (matched by class name).
- `val inAppExcludes: MutableList<String>`: List of package names to be excluded from inApp frames for error tracking
- `val inAppIncludes: MutableList<String>`: List of package names to be considered inApp frames for error tracking

### `PostHogEvent`

```kotlin
data class PostHogEvent(val event: String, val distinctId: String, val properties: MutableMap<String, Any>? = null, val timestamp: Date = Date(), val uuid: UUID? = TimeBasedEpochGenerator.generate(), val type: String? = null, val messageId: UUID? = null, val set: Map<String, Any>? = null, var apiKey: String? = null)
```

The PostHog event data structure accepted by the batch API

#### Properties

- `@SerializedName(value = "api_key")var apiKey: String?`
- `@SerializedName(value = "distinct_id")val distinctId: String`: The distinct Id
- `val event: String`: The event name
- `@SerializedName(value = "message_id")val messageId: UUID? = null`
- `val properties: MutableMap<String, Any>? = null`: All the event properties
- `@SerializedName(value = "$set")val set: Map<String, Any>? = null`
- `val timestamp: Date`: The event timestamp. UTC is preferred; the equivalent instant is serialized in UTC
- `val type: String? = null`
- `val uuid: UUID?`: the UUID v4 is automatically generated and used for deduplication

#### Functions

- `fun isExceptionEvent(): Boolean`: Checks if the event is an exception event ($exception)
- `fun isFatalExceptionEvent(): Boolean`: Checks if the event is a fatal exception event ($exception) and properties ($exception\_level=fatal)

### `PostHogEventName`

```kotlin
enum PostHogEventName : Enum<PostHogEventName>
```

#### Properties

- `val event: String`: The event name sent to PostHog.

#### Entries

- `SNAPSHOT`: Session replay snapshot event.
- `SET`: Sets person properties.
- `IDENTIFY`: Identifies a user.
- `SCREEN`: A screen view.
- `GROUP_IDENTIFY`: Sets group properties.
- `CREATE_ALIAS`: Aliases one distinct ID to another.
- `FEATURE_FLAG_CALLED`: A feature flag was evaluated.
- `FEATURE_VIEW`: A feature was viewed.
- `FEATURE_INTERACTION`: A feature was interacted with.
- `EXCEPTION`: A captured exception.

### `PostHogExceptionStepsConfig`

```kotlin
class PostHogExceptionStepsConfig @JvmOverloads constructor(var enabled: Boolean = true, var maxBytes: Int = 32768)
```

Configuration for exception steps: breadcrumb-style context records that are attached to every captured `$exception` event as `$exception_steps`.

Record steps with com.posthog.PostHog.addExceptionStep.

#### Properties

- `var enabled: Boolean`: Enable recording and attaching exception steps.
- `var maxBytes: Int`: Total UTF-8 byte budget for the rolling step buffer. When adding a step would exceed the budget, the oldest steps are evicted until the total fits. A single step larger than the budget is rejected outright.

### `PostHogIntegration`

```kotlin
interface PostHogIntegration
```

Integration interface for capturing events automatically or adding plugins to the PostHog SDK

#### Inheritors

- `PostHogErrorTrackingAutoCaptureIntegration`:

#### Functions

- `fun install(postHog: PostHogInterface)`: Install the Integration after the SDK is setup that requires a posthog instance to capture events
- `fun onChange()`: Invalidates pending observations when identity, consent, screen, session or close state changes. Called synchronously on the caller's thread; calls may be nested or concurrent. This is a one-shot notification, not a pause/resume boundary. Uninstall must independently clean up.
- `fun onRemoteConfig(loaded: Boolean = true)`: Called when the remote config attempt for the current identity resolves. Each integration is responsible for enabling or disabling features based on the state of the remote config.
- `fun uninstall()`: Uninstall the Integration after the SDK is closed

### `PostHogLogRecord`

```kotlin
data class PostHogLogRecord(val body: String, val level: PostHogLogSeverity = PostHogLogSeverity.INFO, val attributes: Map<String, Any> = emptyMap(), val traceId: String? = null, val spanId: String? = null, val traceFlags: Int? = null, val distinctId: String? = null, val sessionId: String? = null, val screenName: String? = null, val featureFlagKeys: List<String> = emptyList(), val appState: String? = null, val timeUnixNano: String = nanosNow(), val observedTimeUnixNano: String = timeUnixNano)
```

A captured log entry queued for delivery to PostHog's logs ingestion.

You don't construct these directly under normal use — call com.posthog.PostHog.logger methods instead. The record is surfaced as the input to PostHogBeforeSendLog hooks so you can inspect or rewrite it before it ships.

```kotlin
config.logs.addBeforeSend { record: PostHogLogRecord ->
    // Inspect:
    val severity = record.level
    val body = record.body

    // Rewrite via copy() (data class):
    record.copy(body = body.replace(SECRET_REGEX, "[redacted]"))
}
```

#### Properties

- `val appState: String? = null`: `"foreground"` or `"background"` at capture time.
- `val attributes: Map<String, Any>`: Structured per-record attributes attached by the caller.
- `val body: String`: Log message body. Blank records are dropped after `beforeSend`.
- `val distinctId: String? = null`: Snapshot of the active PostHog distinct id at capture time.
- `val featureFlagKeys: List<String>`: Snapshot of feature flag keys that evaluated to active (truthy boolean or any multivariant value) at capture time. Use this to filter logs by flag state in the PostHog UI.
- `val level: PostHogLogSeverity`: Severity; defaults to PostHogLogSeverity.INFO.
- `val observedTimeUnixNano: String`: Advanced/observability field. Defaults to timeUnixNano; differs only when the SDK observes a record after the original timestamp (e.g. replay from persisted storage).
- `val screenName: String? = null`: Last screen name reported via com.posthog.PostHog.screen.
- `val sessionId: String? = null`: Snapshot of the active session id at capture time.
- `val spanId: String? = null`: W3C span id paired with traceId.
- `val timeUnixNano: String`: Advanced/observability field. Capture time as nanoseconds since epoch, encoded as a string to avoid i64 overflow on the JSON wire.
- `val traceFlags: Int? = null`: W3C trace flags byte (e.g. `0x01` for sampled).
- `val traceId: String? = null`: W3C trace id, when capture is part of a distributed trace.

### `PostHogLogSeverity`

```kotlin
enum PostHogLogSeverity : Enum<PostHogLogSeverity>
```

Severity level for a captured log record.

Constants are declared in severity order so `>=` comparisons on severityNumber give the expected result — for example, "warn and worse" is `severity.severityNumber >= PostHogLogSeverity.WARN.severityNumber`.

Maps to OpenTelemetry severity numbers (`TRACE=1`, `DEBUG=5`, `INFO=9`, `WARN=13`, `ERROR=17`, `FATAL=21`) for the OTLP wire format.

#### Usage

```kotlin
// Direct use with the generic logger entry point:
PostHog.logger.log("loading", severity = PostHogLogSeverity.DEBUG)

// Map a string from an external logger (e.g. Timber, slf4j):
val sev = PostHogLogSeverity.from("WARN") ?: PostHogLogSeverity.INFO

// Filter inside a beforeSend hook:
config.logs.addBeforeSend { record ->
    if (record.level.severityNumber < PostHogLogSeverity.INFO.severityNumber) null
    else record
}
```

#### Properties

- `val severityNumber: Int`: OTLP `severityNumber` (1, 5, 9, 13, 17, 21).
- `val severityText: String`: OTLP `severityText` (lower-case).

#### Entries

- `TRACE`: Fine-grained tracing detail.
- `DEBUG`: Debugging detail.
- `INFO`: Normal operation.
- `WARN`: Something unexpected that is not an error.
- `ERROR`: An error.
- `FATAL`: An unrecoverable error.

### `PostHogLogger`

```kotlin
class PostHogLogger
```

Captures application log records and forwards them to PostHog's logs ingestion endpoint.

Obtain via com.posthog.PostHog.logger (static, recommended) or com.posthog.PostHogInterface.logger when you hold a live SDK instance.

#### Kotlin usage

```kotlin
import com.posthog.PostHog
import com.posthog.logs.PostHogLogSeverity

// Severity-specific shortcuts:
PostHog.logger.info("checkout opened")
PostHog.logger.error(
    "payment failed",
    attributes = mapOf("amount_cents" to 1999, "currency" to "USD"),
)

// Generic entry point with a runtime severity:
PostHog.logger.log("rendered cart", severity = PostHogLogSeverity.DEBUG)
```

#### Java usage

```
import com.posthog.PostHog;
import com.posthog.logs.PostHogLogSeverity;

import java.util.Map;

PostHog.Companion.getLogger().info("checkout opened");
PostHog.Companion.getLogger().error(
    "payment failed",
    Map.of("amount_cents", 1999, "currency", "USD")
);
PostHog.Companion.getLogger().log("rendered cart", PostHogLogSeverity.DEBUG, null);
```

#### Attribute values

Attribute values must be JSON-serializable: `String`, `Number`, `Boolean`, `Date`, or lists / maps composed of those types. Custom types without a registered serializer may fail to serialize at flush time — stick to the documented types.

Nested maps and lists are supported. The attributes map is deep-copied at capture time, so you can safely mutate the map (and any nested collections) after the call returns.

#### Drop / redact records

Register a PostHogBeforeSendLog via PostHogLogsConfig.addBeforeSend to drop or redact records before they are enqueued.

#### See also

- `PostHogLogSeverity`:
- `PostHogLogsConfig`:
- `PostHogBeforeSendLog`:

#### Functions

- `fun debug(message: String, attributes: Map<String, Any>? = null)`: Captures a debug-level record. Diagnostic detail useful during development.
- `fun error(message: String, attributes: Map<String, Any>? = null)`: Captures an error-level record. An operation failed; the app may continue.
- `fun fatal(message: String, attributes: Map<String, Any>? = null)`: Captures a fatal-level record. An unrecoverable failure; the app likely cannot continue. Logged records still ship asynchronously, so consider calling com.posthog.PostHog.flush before the process exits.
- `fun info(message: String, attributes: Map<String, Any>? = null)`: Captures an info-level record. Default level for regular runtime events.
- `fun log(message: String, severity: PostHogLogSeverity = PostHogLogSeverity.INFO, attributes: Map<String, Any>? = null)`: Captures a log record at the given severity.
- `fun trace(message: String, attributes: Map<String, Any>? = null)`: Captures a finest-grained trace-level record. Usually only enabled while diagnosing — high-volume; will trip the PostHogLogsConfig.rateCapMaxLogs cap quickly in production.
- `fun warn(message: String, attributes: Map<String, Any>? = null)`: Captures a warn-level record. Something unexpected happened but the operation continued.

### `PostHogLogsConfig`

```kotlin
class PostHogLogsConfig
```

Configuration for PostHog's logs ingestion.

Mutate fields on the PostHogConfig.logs property **before** calling `PostHog.setup(config)` — most knobs are snapshotted at setup and later changes are ignored. Per-field mutability is documented on each property.

#### Kotlin

```kotlin
val config = PostHogAndroidConfig(projectToken).apply {
    logs.serviceName = "checkout-android"
    logs.environment = "production"
    logs.resourceAttributes = mapOf("region" to "us-east-1")

    // Redact PII before records leave the device:
    logs.addBeforeSend { record ->
        if (record.body.contains("@")) {
            record.copy(body = record.body.replace(EMAIL_REGEX, "[redacted]"))
        } else {
            record
        }
    }
}
PostHogAndroid.setup(this, config)
```

#### Java

```
PostHogAndroidConfig config = new PostHogAndroidConfig(projectToken);
config.getLogs().setServiceName("checkout-android");
config.getLogs().setEnvironment("production");
config.getLogs().addBeforeSend(record ->
    record.getBody().contains("@") ? null : record
);
PostHogAndroid.setup(context, config);
```

#### Properties

- `val beforeSendList: List<PostHogBeforeSendLog>`: Snapshot of the currently registered `beforeSend` hooks, in registration order. Useful for diagnostics and test assertions; the returned list is an immutable copy.
- `var environment: String?`: Environment the app is running in. Lets you separate production logs from staging/development noise in the PostHog UI. Common values: `"production"`, `"staging"`, `"development"`. Omitted from the payload entirely when `null`.
- `var flushAt: Int`: Threshold at which the queue triggers a flush automatically.
- `var flushIntervalSeconds: Int`: How often the queue checks for records to flush, in seconds.
- `var maxBatchSize: Int`: Initial maximum number of records sent in a single request.
- `var maxBufferSize: Int`: Maximum number of records held on disk before the oldest is dropped (FIFO).
- `var rateCapMaxLogs: Int`: Maximum number of log records accepted per rateCapWindowSeconds before subsequent records in the same window are dropped at capture time.
- `var rateCapWindowSeconds: Int`: Tumbling window in seconds used by rateCapMaxLogs. The counter resets when wall-clock time advances past the window. Set to `0` (or any non-positive value) to disable the cap entirely.
- `var resourceAttributes: Map<String, Any>`: Additional metadata attached to every batch of records — anything you'd want to filter or segment by in the PostHog UI that isn't record-specific (e.g. region, build flavor, device model).
- `var serviceName: String?`: Name of the application sending logs. Used to group records in the PostHog UI when multiple apps report to the same project, and to match values used by other observability tooling.
- `var serviceVersion: String?`: Version of the application sending logs, typically your release version string. Lets you filter and segment log records by app version in the PostHog UI.

#### Functions

- `fun addBeforeSend(hook: PostHogBeforeSendLog)`: Adds a `beforeSend` hook that can mutate or drop log records before they reach the queue.
- `fun removeBeforeSend(hook: PostHogBeforeSendLog)`: Removes a previously-added `beforeSend` hook. Pass the same lambda reference you originally registered.

### `PostHogMaskModifier`

```kotlin
object PostHogMaskModifier
```

Jetpack Compose modifiers for controlling masking in Android session replay captures.

#### Functions

- `fun Modifier.postHogMask(isEnabled: Boolean = true): Modifier`: Modifier to mask elements in the session replay.
- `fun Modifier.postHogUnmask(isEnabled: Boolean = true): Modifier`: Modifier to explicitly unmask elements in the session replay.

### `PostHogNextSurveyQuestion`

```kotlin
class PostHogNextSurveyQuestion(val questionIndex: Int, val isSurveyCompleted: Boolean = false)
```

Represents the next state after a user responds to a survey question.

#### Properties

- `val isSurveyCompleted: Boolean = false`: Whether all questions have been answered and the survey is complete. Depending on the survey appearance configuration, you may want to show the "Thank you" message or dismiss the survey at this point
- `val questionIndex: Int`: The index of the next question to be displayed (0-based)

### `PostHogOkHttpInterceptor`

```kotlin
class PostHogOkHttpInterceptor(captureNetworkTelemetry: Boolean = true, postHog: PostHogInterface? = null) : Interceptor
```

Interceptor that captures network telemetry for session replay and, when PostHogConfig.tracingHeaders is configured, injects PostHog tracing headers into matching OkHttp requests.

Install this interceptor on each okhttp3.OkHttpClient whose requests should be captured or annotated with tracing headers.

#### Parameters

- `captureNetworkTelemetry`: Whether to capture network timing telemetry for session replay.
- `postHog`: Optional SDK instance to use; when omitted, the global PostHog singleton is used.

#### Functions

- `fun intercept(chain: Interceptor.Chain): Response`

### `PostHogOnFeatureFlags`

```kotlin
fun interface PostHogOnFeatureFlags
```

Interface for being notified once feature flags has been loaded

#### Functions

- `fun loaded()`: The method that is called when feature flags are loaded

### `PostHogPropertiesSanitizer`

```kotlin
fun interface PostHogPropertiesSanitizer
```

Hook to sanitize the event properties

#### Functions

- `fun sanitize(properties: MutableMap<String, Any>): Map<String, Any>`: Sanitizes the event properties

### `PostHogScreenshotColorMode`

```kotlin
enum PostHogScreenshotColorMode : Enum<PostHogScreenshotColorMode>
```

Pixel format for session replay screenshot captures, independent of resolution and compression.

#### Entries

- `ARGB_8888`: Four bytes per pixel, preserving alpha and eight bits per color channel before encoding.
- `RGB_565`: Two bytes per pixel, with reduced color precision and no alpha channel.

### `PostHogSessionReplayConfig`

```kotlin
class PostHogSessionReplayConfig @JvmOverloads constructor(var maskAllTextInputs: Boolean = true, var maskAllImages: Boolean = true, var captureLogcat: Boolean = true, var drawableConverter: PostHogDrawableConverter? = null, var screenshot: Boolean = false, var debouncerDelayMs: Long = 1000, var throttleDelayMs: Long = 1000, var sampleRate: Double? = null)
```

Android session replay capture options.

Use this as `PostHogAndroidConfig.sessionReplayConfig` when enabling session replay through `PostHogConfig.sessionReplay`.

#### Properties

- `var captureLogcat: Boolean`: Enable capturing of logcat as console events Defaults to true
- `var captureTouches: Boolean`: Capture touch coordinates in session replay. Defaults to true. Set before SDK setup. Runtime changes are not supported. Screenshot and view capture are unaffected. Touches inside views marked `ph-no-capture` or `postHogMask()` nodes are never recorded. Disable this when other touch positions could reveal sensitive input, such as taps on fields masked only by `maskAllTextInputs`.
- `var debouncerDelayMs: Long`: Debouncer delay used to reduce the number of snapshots captured and reduce performance impact. This is used for capturing the view as a wireframe or screenshot. The lower the number, the more snapshots are captured and the higher the performance impact. Defaults to 1000ms = 1s. Ps: it was 500ms by default until version 3.8.2.
- `var drawableConverter: PostHogDrawableConverter?`: Converts custom Drawable to Bitmap By default PostHog tries to convert the Drawable to Bitmap, the supported types are BitmapDrawable, ColorDrawable, GradientDrawable, InsetDrawable, LayerDrawable, RippleDrawable
- `var maskAllImages: Boolean`: Enable masking of all images to a placeholder Defaults to true
- `var maskAllTextInputs: Boolean`: Enable masking of all text and text input fields Defaults to true
- `var sampleRate: Double?`: Local sample rate for session recording, a value between 0.0 and 1.0. When set, this takes precedence over the remote config sample rate. `null` means no local override, so the SDK uses the remote config value. Defaults to `null`.
- `var screenshot: Boolean`: By default Session replay will capture all the views on the screen as a wireframe, By enabling this option, PostHog will capture the screenshot of the screen. The screenshot may contain sensitive information, use with caution. In both modes, windows that set `FLAG_SECURE` are recorded as a solid black frame, and their touches are not recorded.
- `@Volatilevar screenshotColorMode: PostHogScreenshotColorMode`: Pixel format used for screenshot captures. Defaults to PostHogScreenshotColorMode.ARGB\_8888 to preserve alpha and color precision before lossy WebP compression. PostHogScreenshotColorMode.RGB\_565 uses less bitmap memory but reduces color precision and removes alpha, making transparent activity window regions appear black. In composed scenes, dialogs use ARGB\_8888 to preserve transparency. Devices that reject RGB\_565 fall back to ARGB\_8888. Does not enable screenshot capture.
- `@Volatilevar screenshotCompressionQuality: Int`: WebP compression quality for screenshots, clamped to 0..100. Defaults to 30. Higher values generally retain more detail and produce larger payloads. Compression is lossy, except on Android 10 (API 29), where the platform's legacy WebP encoder uses lossless compression at quality 100. Does not change screenshot resolution or enable screenshot capture.
- `@Volatilevar screenshotScale: Float?`: Multiplier for the physical width and height of screenshot captures, clamped to 0.1..1.0. `null` (default) captures one pixel per dp (1/density), so screens with a density of 1 or less capture at full resolution. For example, 0.5 captures half the width and height. Dimensions are rounded up to at least one pixel; the logical replay viewport is unchanged. NaN and infinite values reset the scale to `null`. Does not change compression quality, color mode, or enable screenshot capture.
- `var throttleDelayMs: Long`: Throttling delay used to reduce the number of snapshots captured and reduce performance impact. This is used for capturing the view as a wireframe or screenshot. The lower the number, the more snapshots are captured and the higher the performance impact. Defaults to 1000ms = 1s.
- `var verifyScreenshotMaskAlignment: Boolean`: Verifies mask alignment for session replay screenshots. This can preserve screenshots during pixel-only redraws, including continuously animated content, but performs additional view hierarchy walks while a screenshot is captured. Defaults to false. Windows with a Compose root always use the verified path regardless of this flag, so setting it to false does not disable verification for them.

### `PostHogSurveyResponse`

```kotlin
sealed class PostHogSurveyResponse
```

Represents a user's response to a survey question.

#### Inheritors

- `Text`:
- `SingleChoice`:
- `MultipleChoice`:
- `Rating`:
- `Link`:

#### Functions

- `fun toResponseValue(): Any?`: Creates a response value for sending to the PostHog API.

### `PostHogSurveysConfig`

```kotlin
class PostHogSurveysConfig
```

Configuration for PostHog Surveys feature.

#### Properties

- `var overrideDisplayLanguage: String?`: Optional explicit override for the language used when rendering surveys.
- `var surveysDelegate: PostHogSurveysDelegate`: Delegate responsible for managing survey presentation in your app. Handles survey rendering, response collection, and lifecycle events. You can provide your own delegate for a custom survey presentation.

### `PostHogSurveysDefaultDelegate`

```kotlin
class PostHogSurveysDefaultDelegate(config: PostHogConfig? = null) : PostHogSurveysDelegate
```

Default implementation of PostHogSurveysDelegate. This implementation doesn't render any UI, it only logs when survey methods are called.

Users should implement their own delegate to handle survey rendering.

#### Functions

- `fun cleanupSurveys()`: Called when surveys should be cleaned up. This default implementation only logs that cleanup was requested.
- `fun renderSurvey(survey: PostHogDisplaySurvey, onSurveyShown: OnPostHogSurveyShown, onSurveyResponse: OnPostHogSurveyResponse, onSurveyClosed: OnPostHogSurveyClosed)`: Called when a survey should be rendered. This default implementation only logs that a survey was requested to be shown.

### `PostHogSurveysDelegate`

```kotlin
interface PostHogSurveysDelegate
```

Delegate responsible for managing survey presentation in your app. Handles survey rendering, response collection, and lifecycle events. You can provide your own delegate for a custom survey presentation.

#### Inheritors

- `PostHogSurveysDefaultDelegate`:
- `PostHogSurveysResumeAwareDelegate`:

#### Functions

- `fun cleanupSurveys()`: Called when surveys are stopped to clean up any UI elements and reset the survey display state. This method should handle the dismissal of any active surveys and cleanup of associated resources.
- `fun renderSurvey(survey: PostHogDisplaySurvey, onSurveyShown: OnPostHogSurveyShown, onSurveyResponse: OnPostHogSurveyResponse, onSurveyClosed: OnPostHogSurveyClosed)`: Called when an activated PostHog survey needs to be rendered on the app's UI

### `PostHogSurveysResumeAwareDelegate`

```kotlin
interface PostHogSurveysResumeAwareDelegate : PostHogSurveysDelegate
```

Optional capability for delegates that restore unfinished surveys.

Delegates without this capability, or returning false, start fresh at question 0. Opting in requires starting at PostHogDisplaySurvey.initialQuestionIndex; the SDK restores saved answers and the submission ID for that attempt.

For example, a renderer that honors the initial question can declare: `override val supportsSurveyResume: Boolean = true`.

#### Properties

- `val supportsSurveyResume: Boolean`: Whether this delegate honors PostHogDisplaySurvey.initialQuestionIndex, so the SDK resumes surveys.

#### Functions

- `fun cleanupSurveys()`: Called when surveys are stopped to clean up any UI elements and reset the survey display state. This method should handle the dismissal of any active surveys and cleanup of associated resources.
- `fun renderSurvey(survey: PostHogDisplaySurvey, onSurveyShown: OnPostHogSurveyShown, onSurveyResponse: OnPostHogSurveyResponse, onSurveyClosed: OnPostHogSurveyClosed)`: Called when an activated PostHog survey needs to be rendered on the app's UI

### `RatingSurveyQuestion`

```kotlin
class RatingSurveyQuestion(val display: SurveyRatingDisplayType?, val scale: Int?, val lowerBoundLabel: String?, val upperBoundLabel: String?) : SurveyQuestion
```

#### Properties

- `val branching: SurveyQuestionBranching? = null`
- `val buttonText: String? = null`
- `val description: String? = null`
- `val descriptionContentType: SurveyTextContentType? = null`
- `val display: SurveyRatingDisplayType?`
- `val id: String? = null`
- `val lowerBoundLabel: String?`
- `val optional: Boolean? = null`
- `val question: String? = null`
- `val scale: Int?`
- `val skipSubmitButton: Boolean? = null`
- `val translations: Map<String, SurveyQuestionTranslation>? = null`
- `val type: SurveyQuestionType? = null`
- `val upperBoundLabel: String?`

### `SingleSurveyQuestion`

```kotlin
data class SingleSurveyQuestion(val choices: List<String>?, val hasOpenChoice: Boolean?, val shuffleOptions: Boolean?) : SurveyQuestion
```

#### Properties

- `val branching: SurveyQuestionBranching? = null`
- `val buttonText: String? = null`
- `val choices: List<String>?`
- `val description: String? = null`
- `val descriptionContentType: SurveyTextContentType? = null`
- `val hasOpenChoice: Boolean?`
- `val id: String? = null`
- `val optional: Boolean? = null`
- `val question: String? = null`
- `val shuffleOptions: Boolean?`
- `val skipSubmitButton: Boolean? = null`
- `val translations: Map<String, SurveyQuestionTranslation>? = null`
- `val type: SurveyQuestionType? = null`

### `Survey`

```kotlin
data class Survey(val id: String, val name: String, val type: SurveyType, val questions: List<SurveyQuestion>, val description: String?, val featureFlagKeys: List<SurveyFeatureFlagKeyValue>?, val linkedFlagKey: String?, val targetingFlagKey: String?, val internalTargetingFlagKey: String?, val conditions: SurveyConditions?, val appearance: SurveyAppearance?, val currentIteration: Int?, val currentIterationStartDate: Date?, val startDate: Date?, val endDate: Date?, val schedule: SurveySchedule?, val translations: Map<String, SurveyTranslation>? = null, val enablePartialResponses: Boolean? = null)
```

#### Properties

- `val appearance: SurveyAppearance?`
- `val conditions: SurveyConditions?`
- `@SerializedName(value = "current_iteration")val currentIteration: Int?`
- `@SerializedName(value = "current_iteration_start_date")val currentIterationStartDate: Date?`
- `val description: String?`
- `@SerializedName(value = "enable_partial_responses")val enablePartialResponses: Boolean? = null`
- `@SerializedName(value = "end_date")val endDate: Date?`
- `@SerializedName(value = "feature_flag_keys")val featureFlagKeys: List<SurveyFeatureFlagKeyValue>?`
- `val id: String`
- `@SerializedName(value = "internal_targeting_flag_key")val internalTargetingFlagKey: String?`
- `@SerializedName(value = "linked_flag_key")val linkedFlagKey: String?`
- `val questions: List<SurveyQuestion>`
- `val schedule: SurveySchedule?`
- `@SerializedName(value = "start_date")val startDate: Date?`
- `@SerializedName(value = "targeting_flag_key")val targetingFlagKey: String?`
- `val translations: Map<String, SurveyTranslation>? = null`
- `val type: SurveyType`

### `SurveyAppearance`

```kotlin
data class SurveyAppearance(val position: SurveyAppearancePosition?, val fontFamily: String?, val backgroundColor: String?, val submitButtonColor: String?, val submitButtonText: String?, val submitButtonTextColor: String?, val textColor: String?, val descriptionTextColor: String?, val ratingButtonColor: String?, val ratingButtonActiveColor: String?, val ratingButtonHoverColor: String?, val inputBackground: String?, val inputTextColor: String?, val whiteLabel: Boolean?, val autoDisappear: Boolean?, val displayThankYouMessage: Boolean?, val thankYouMessageHeader: String?, val thankYouMessageDescription: String?, val thankYouMessageDescriptionContentType: SurveyTextContentType?, val thankYouMessageCloseButtonText: String?, val borderColor: String, val placeholder: String?, val shuffleQuestions: Boolean?, val surveyPopupDelaySeconds: Double?, val widgetType: SurveyAppearanceWidgetType?, val widgetSelector: String?, val widgetLabel: String?, val widgetColor: String?, val displayIntroScreen: Boolean? = null, val introScreenHeader: String? = null, val introScreenDescription: String? = null, val introScreenDescriptionContentType: SurveyTextContentType? = null, val introScreenButtonText: String? = null)
```

#### Properties

- `val autoDisappear: Boolean?`
- `val backgroundColor: String?`
- `val borderColor: String`
- `val descriptionTextColor: String?`
- `val displayIntroScreen: Boolean? = null`
- `val displayThankYouMessage: Boolean?`
- `val fontFamily: String?`
- `val inputBackground: String?`
- `val inputTextColor: String?`
- `val introScreenButtonText: String? = null`
- `val introScreenDescription: String? = null`
- `val introScreenDescriptionContentType: SurveyTextContentType? = null`
- `val introScreenHeader: String? = null`
- `val placeholder: String?`
- `val position: SurveyAppearancePosition?`
- `val ratingButtonActiveColor: String?`
- `val ratingButtonColor: String?`
- `val ratingButtonHoverColor: String?`
- `val shuffleQuestions: Boolean?`
- `val submitButtonColor: String?`
- `val submitButtonText: String?`
- `val submitButtonTextColor: String?`
- `val surveyPopupDelaySeconds: Double?`
- `val textColor: String?`
- `val thankYouMessageCloseButtonText: String?`
- `val thankYouMessageDescription: String?`
- `val thankYouMessageDescriptionContentType: SurveyTextContentType?`
- `val thankYouMessageHeader: String?`
- `val whiteLabel: Boolean?`
- `val widgetColor: String?`
- `val widgetLabel: String?`
- `val widgetSelector: String?`
- `val widgetType: SurveyAppearanceWidgetType?`

### `SurveyAppearancePosition`

```kotlin
enum SurveyAppearancePosition : Enum<SurveyAppearancePosition>
```

#### Properties

- `val value: String`

#### Entries

- `LEFT`
- `RIGHT`
- `CENTER`

### `SurveyAppearanceWidgetType`

```kotlin
enum SurveyAppearanceWidgetType : Enum<SurveyAppearanceWidgetType>
```

#### Properties

- `val value: String`

#### Entries

- `BUTTON`
- `TAB`
- `SELECTOR`

### `SurveyConditions`

```kotlin
data class SurveyConditions @JvmOverloads constructor(val url: String?, val urlMatchType: SurveyMatchType?, val selector: String?, val deviceTypes: List<String>?, val deviceTypesMatchType: SurveyMatchType?, val seenSurveyWaitPeriodInDays: Int?, val events: SurveyEventConditions?, val linkedFlagVariant: String? = null)
```

#### Properties

- `val deviceTypes: List<String>?`
- `val deviceTypesMatchType: SurveyMatchType?`
- `val events: SurveyEventConditions?`
- `val linkedFlagVariant: String? = null`: Required variant of the linked flag; "any" accepts any enabled value.
- `val seenSurveyWaitPeriodInDays: Int?`
- `val selector: String?`
- `val url: String?`
- `val urlMatchType: SurveyMatchType?`

### `SurveyEventCondition`

```kotlin
data class SurveyEventCondition(val name: String, val propertyFilters: Map<String, SurveyPropertyFilter>? = null)
```

#### Properties

- `val propertyFilters: Map<String, SurveyPropertyFilter>? = null`

### `SurveyEventConditions`

```kotlin
data class SurveyEventConditions(val repeatedActivation: Boolean?, val values: List<SurveyEventCondition>)
```

#### Properties

- `val repeatedActivation: Boolean?`

### `SurveyFeatureFlagKeyValue`

```kotlin
data class SurveyFeatureFlagKeyValue(val key: String, val value: String?)
```

#### Properties

- `val key: String`
- `val value: String?`

### `SurveyMatchType`

```kotlin
enum SurveyMatchType : Enum<SurveyMatchType>
```

#### Properties

- `val value: String`

#### Entries

- `REGEX`
- `NOT_REGEX`
- `EXACT`
- `IS_NOT`
- `I_CONTAINS`
- `NOT_I_CONTAINS`
- `GT`
- `LT`

### `SurveyPropertyFilter`

```kotlin
class SurveyPropertyFilter(val values: List<String>, val operator: SurveyMatchType)
```

#### Properties

- `val operator: SurveyMatchType`

### `SurveyQuestion`

```kotlin
class SurveyQuestion
```

#### Inheritors

- `OpenSurveyQuestion`:
- `LinkSurveyQuestion`:
- `RatingSurveyQuestion`:
- `SingleSurveyQuestion`:
- `MultipleSurveyQuestion`:

#### Properties

- `val branching: SurveyQuestionBranching? = null`
- `val buttonText: String? = null`
- `val description: String? = null`
- `val descriptionContentType: SurveyTextContentType? = null`
- `val id: String? = null`
- `val optional: Boolean? = null`
- `val question: String? = null`
- `val skipSubmitButton: Boolean? = null`
- `val translations: Map<String, SurveyQuestionTranslation>? = null`
- `val type: SurveyQuestionType? = null`

### `SurveyQuestionBranching`

```kotlin
sealed class SurveyQuestionBranching
```

#### Inheritors

- `Next`:
- `End`:
- `ResponseBased`:
- `SpecificQuestion`:

### `SurveyQuestionBranchingType`

```kotlin
enum SurveyQuestionBranchingType : Enum<SurveyQuestionBranchingType>
```

#### Properties

- `val value: String`

#### Entries

- `NEXT_QUESTION`
- `END`
- `RESPONSE_BASED`
- `SPECIFIC_QUESTION`

### `SurveyQuestionTranslation`

```kotlin
data class SurveyQuestionTranslation(val question: String? = null, val description: String? = null, val buttonText: String? = null, val link: String? = null, val lowerBoundLabel: String? = null, val upperBoundLabel: String? = null, val choices: List<String>? = null)
```

Localized overrides for a survey question.

Fields are a superset across question types: `link` only applies to link questions, `lowerBoundLabel` / `upperBoundLabel` only to rating questions, and `choices` only to single/multiple choice questions. Irrelevant fields are ignored for other types.

#### Properties

- `val buttonText: String? = null`
- `val choices: List<String>? = null`
- `val description: String? = null`
- `val link: String? = null`
- `val lowerBoundLabel: String? = null`
- `val question: String? = null`
- `val upperBoundLabel: String? = null`

### `SurveyQuestionType`

```kotlin
enum SurveyQuestionType : Enum<SurveyQuestionType>
```

#### Properties

- `val value: String`

#### Entries

- `OPEN`
- `LINK`
- `RATING`
- `MULTIPLE_CHOICE`
- `SINGLE_CHOICE`

### `SurveyRatingDisplayType`

```kotlin
enum SurveyRatingDisplayType : Enum<SurveyRatingDisplayType>
```

#### Properties

- `val value: String`

#### Entries

- `NUMBER`
- `EMOJI`

### `SurveySchedule`

```kotlin
enum SurveySchedule : Enum<SurveySchedule>
```

#### Properties

- `val value: String`

#### Entries

- `ONCE`
- `RECURRING`
- `ALWAYS`

### `SurveyTextContentType`

```kotlin
enum SurveyTextContentType : Enum<SurveyTextContentType>
```

#### Properties

- `val value: String`

#### Entries

- `HTML`
- `TEXT`

### `SurveyTranslation`

```kotlin
data class SurveyTranslation(val name: String? = null, val thankYouMessageHeader: String? = null, val thankYouMessageDescription: String? = null, val thankYouMessageCloseButtonText: String? = null, val introScreenHeader: String? = null, val introScreenDescription: String? = null, val introScreenButtonText: String? = null)
```

Localized overrides for a survey, keyed by language code (e.g. "fr", "pt-BR").

The survey-level `description` is intentionally NOT translatable — it is only used for internal previews and never rendered to end users.

#### Properties

- `val introScreenButtonText: String? = null`
- `val introScreenDescription: String? = null`
- `val introScreenHeader: String? = null`
- `val thankYouMessageCloseButtonText: String? = null`
- `val thankYouMessageDescription: String? = null`
- `val thankYouMessageHeader: String? = null`

### `SurveyType`

```kotlin
enum SurveyType : Enum<SurveyType>
```

#### Properties

- `val value: String`

#### Entries

- `POPOVER`
- `API`
- `WIDGET`

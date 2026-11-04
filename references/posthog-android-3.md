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

| | |
|---|---|
| context | Android context; the application context is retained internally. |
| config | Android SDK configuration. |

### `PostHogAndroid.with`

```kotlin
fun <T : PostHogAndroidConfig> with(context: Context, config: T): PostHogInterface
```

Creates and returns a configured SDK instance that you can hold and pass around.

Default PostHog integrations are installed only on the first instance created by either setup or with. The first instance in the host app owns those integrations, so setup order matters.

#### Return

The configured PostHog client instance.

#### Parameters

| | |
|---|---|
| context | Android context; the application context is retained internally. |
| config | Android SDK configuration. |

### `PostHog.setup`

```kotlin
fun <T : PostHogConfig> setup(config: T)
```

Setup the SDK

#### Parameters

| | |
|---|---|
| config | the SDK configuration |

### `PostHog.with`

```kotlin
fun <T : PostHogConfig> with(config: T): PostHogInterface
```

Sets up the SDK and returns an instance that you can hold and pass around.

#### Return

The configured PostHog client instance.

#### Parameters

| | |
|---|---|
| config | SDK configuration. |

## Capture

### `PostHog.capture`

```kotlin
fun capture(event: String, distinctId: String? = null, properties: Map<String, Any>? = null, userProperties: Map<String, Any>? = null, userPropertiesSetOnce: Map<String, Any>? = null, groups: Map<String, String>? = null, timestamp: Date? = null)
```

Captures events

#### Parameters

| | |
|---|---|
| event | the event name |
| distinctId | the distinctId, the generated distinctId is used if not given |
| properties | the custom properties |
| userProperties | the user properties, set as a &quot;$set&quot; property, Docs https://posthog.com/docs/product-analytics/user-properties |
| userPropertiesSetOnce | the user properties to set only once, set as a &quot;$set_once&quot; property, Docs https://posthog.com/docs/product-analytics/user-properties |
| groups | the groups, set as a &quot;$groups&quot; property, Docs https://posthog.com/docs/product-analytics/group-analytics |
| timestamp | the event timestamp override. UTC is preferred; the equivalent instant is serialized in UTC. If omitted, the current time is used |

## Identify

### `PostHog.identify`

```kotlin
fun identify(distinctId: String, userProperties: Map<String, Any>? = null, userPropertiesSetOnce: Map<String, Any>? = null)
```

Identifies the user Docs https://posthog.com/docs/product-analytics/identify

#### Parameters

| | |
|---|---|
| distinctId | the distinctId |
| userProperties | the user properties, set as a &quot;$set&quot; property, Docs https://posthog.com/docs/product-analytics/user-properties |
| userPropertiesSetOnce | the user properties to set only once, set as a &quot;$set_once&quot; property, Docs https://posthog.com/docs/product-analytics/user-properties |

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

| | |
|---|---|
| message | the log message body; blank messages are dropped |
| severity | the log severity, defaults to PostHogLogSeverity.INFO |
| attributes | optional structured attributes; values must be JSON-serializable (`String`, `Number`, `Boolean`, `Date`, lists or maps of the same) |
| traceId | optional 32-character lowercase hex W3C trace id. The ingestion service zeroes ids that are not exactly 16 bytes. |
| spanId | optional 16-character lowercase hex W3C span id. The ingestion service zeroes ids that are not exactly 8 bytes. |
| traceFlags | optional W3C trace flags bitfield (bit 0 is the `sampled` flag); emitted on the wire as `flags`. |

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

| | |
|---|---|
| alias | the alias |

## Capture exception

### `PostHog.captureException`

```kotlin
fun captureException(throwable: Throwable, properties: Map<String, Any>? = null)
```

Captures exceptions

#### Parameters

| | |
|---|---|
| throwable | the Throwable error |
| properties | the custom properties |

## Debug

### `PostHog.debug`

```kotlin
fun debug(enable: Boolean = true)
```

Enables or disables the debug mode

#### Parameters

| | |
|---|---|
| enable | true to enable SDK debug logging, false to disable it. |

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

| | |
|---|---|
| message | a non-empty description of the step |
| properties | optional user-supplied properties (the reserved keys `$message` and `$timestamp` are stripped) |

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

| | |
|---|---|
| key | the Key |
| defaultValue | the default value if not found |
| sendFeatureFlagEvent | (optional) If false, we won't send an $feature_flag_called event to PostHog. |

## Get feature flag payload

### `PostHog.getFeatureFlagPayload`

```kotlin
fun getFeatureFlagPayload(key: String, defaultValue: Any? = null): Any?
```

---

#### Deprecated

Use getFeatureFlagResult() instead; note it sends the $feature_flag_called event by default.

#### Replace with

```kotlin
getFeatureFlagResult(key)?.payload
```
---

Returns the feature flag payload Docs https://posthog.com/docs/feature-flags and https://posthog.com/docs/experiments

#### Return

The feature flag payload, or defaultValue if not found.

#### Parameters

| | |
|---|---|
| key | the Key |
| defaultValue | the default value if not found |

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

| | |
|---|---|
| key | The feature flag key |
| sendFeatureFlagEvent | If false, won't send $feature_flag_called event to PostHog |

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

| | |
|---|---|
| type | the Group type |
| key | the Group key |
| groupProperties | the Group properties, set as a &quot;$group_set&quot; property, Docs https://posthog.com/docs/product-analytics/group-analytics |

## Is feature enabled

### `PostHog.isFeatureEnabled`

```kotlin
fun isFeatureEnabled(key: String, defaultValue: Boolean = false, sendFeatureFlagEvent: Boolean? = null): Boolean
```

Returns if a feature flag is enabled, the feature flag must be a Boolean Docs https://posthog.com/docs/feature-flags and https://posthog.com/docs/experiments

#### Return

Whether the feature flag is enabled.

#### Parameters

| | |
|---|---|
| key | the Key |
| defaultValue | the default value if not found, false if not given |
| sendFeatureFlagEvent | (optional) If false, we won't send an $feature_flag_called event to PostHog. |

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

## Register

### `PostHog.register`

```kotlin
fun register(key: String, value: Any)
```

Register a property to always be sent with all the following events until you call unregister with the same key PostHogPreferences.ALL_INTERNAL_KEYS are not allowed since they are internal and used by the SDK only.

#### Parameters

| | |
|---|---|
| key | the Key |
| value | the Value |

## Reload feature flags

### `PostHog.reloadFeatureFlags`

```kotlin
fun reloadFeatureFlags(onFeatureFlags: PostHogOnFeatureFlags? = null)
```

Reloads the feature flags

#### Parameters

| | |
|---|---|
| onFeatureFlags | the callback to get notified once feature flags is ready to use |

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

| | |
|---|---|
| type | Optional group type to clear. If null, clears all group properties. |
| reloadFeatureFlags | Whether to automatically reload feature flags after resetting properties |

## Reset person properties for flags

### `PostHog.resetPersonPropertiesForFlags`

```kotlin
fun resetPersonPropertiesForFlags(reloadFeatureFlags: Boolean = true)
```

Resets all person properties that were set for feature flag evaluation.

#### Parameters

| | |
|---|---|
| reloadFeatureFlags | Whether to automatically reload feature flags after resetting properties |

## Screen

### `PostHog.screen`

```kotlin
fun screen(screenTitle: String, properties: Map<String, Any>? = null)
```

Captures a screen view event

#### Parameters

| | |
|---|---|
| screenTitle | the screen title. It takes precedence over a `$screen_name` key in properties. |
| properties | the custom properties |

## Set group properties for flags

### `PostHog.setGroupPropertiesForFlags`

```kotlin
fun setGroupPropertiesForFlags(type: String, groupProperties: Map<String, Any>, reloadFeatureFlags: Boolean = true)
```

Sets properties for a specific group type to include when evaluating feature flags.

#### Parameters

| | |
|---|---|
| type | The group type identifier (e.g., &quot;organization&quot;, &quot;team&quot;) |
| groupProperties | Dictionary of properties to set for this group type |
| reloadFeatureFlags | Whether to automatically reload feature flags after setting properties |

## Set person properties

### `PostHog.setPersonProperties`

```kotlin
fun setPersonProperties(userPropertiesToSet: Map<String, Any>? = null, userPropertiesToSetOnce: Map<String, Any>? = null)
```

Sets properties on the person profile associated with the current distinct_id. Learn more about identifying users

Updates user properties that are stored with the person profile in PostHog. If `personProfiles` is set to `IDENTIFIED_ONLY` and no profile exists, this will create one.

This method sends a `$set` event to PostHog.

#### Parameters

| | |
|---|---|
| userPropertiesToSet | Optional: Properties to store about the user. These will overwrite existing values. |
| userPropertiesToSetOnce | Optional: Properties to store about the user only if not previously set. Note: For feature flag evaluations, if the same key is present in both parameters, the value from userPropertiesToSet will take precedence. |

## Set person properties for flags

### `PostHog.setPersonPropertiesForFlags`

```kotlin
fun setPersonPropertiesForFlags(userProperties: Map<String, Any>, reloadFeatureFlags: Boolean = true)
```

Sets person properties that will be included in feature flag evaluation requests.

#### Parameters

| | |
|---|---|
| userProperties | Dictionary of person properties to include in flag evaluation |
| reloadFeatureFlags | Whether to automatically reload feature flags after setting properties |

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

| | |
|---|---|
| resumeCurrent | Whether to resume session replay of current session (true) or start a new session (false). |

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

| | |
|---|---|
| key | the Key |

## SDK utils

### `PostHog.captureFeatureInteraction`

```kotlin
fun captureFeatureInteraction(flag: String, flagVariant: String? = null)
```

Captures a feature interaction event when a user interacts with a feature.

#### Parameters

| | |
|---|---|
| flag | The feature flag key |
| flagVariant | Optional variant key. |

### `PostHog.captureFeatureView`

```kotlin
fun captureFeatureView(flag: String, flagVariant: String? = null)
```

Captures a feature view event when a user sees a feature in the UI.

#### Parameters

| | |
|---|---|
| flag | The feature flag key. |
| flagVariant | Optional variant key. |

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

| | |
|---|---|
| title | the notification title, attached as `$notification_title` when non-empty |
| body | the notification body, attached as `$notification_body` when non-empty |
| payload | the notification data payload; its `posthog` entry is spread into `$notification_*` props |
| action | the tapped notification action identifier, attached as `$notification_action`; leave `null` for a plain tap/open (the default action carries no identifier) |

### `PostHog.endSession`

```kotlin
fun endSession()
```

Ends a session The SDK will automatically end a session when you call close On Android, the SDK will automatically end a session when the app is in the background for at least 30 minutes

### `PostHog.getConfig`

```kotlin
fun <T : PostHogConfig> getConfig(): T?
```

### `PostHog.getDeviceId`

```kotlin
fun getDeviceId(): String
```

Returns the stable device identifier used for device-level feature flag bucketing. This ID persists across identify and reset calls, only changing on a fresh app install, manual cache clearing, or OS-initiated storage cleanup.

#### Return

The device ID, or an empty string if not yet initialized

### `PostHog.isSessionActive`

```kotlin
fun isSessionActive(): Boolean
```

Returns if a session is active

#### Return

Whether a session is currently active.

### `PostHog.optOut`

```kotlin
fun optOut()
```

Disables the SDK to capture events until you optIn again

### `PostHog.registerPushNotificationToken`

```kotlin
fun registerPushNotificationToken(deviceToken: String, appId: String)
```

Registers a push notification device token with PostHog so the backend can deliver push notifications sent from PostHog Workflows to this device.

The token is persisted and (re)sent with the current distinctId; transient failures are retried automatically. On Android, when Firebase Messaging is on the classpath and `capturePushNotificationSubscriptions` is enabled, this is called for you at startup. Call it manually to forward Firebase's `onNewToken` or `onRegistered` callback. An explicit call refreshes a previously delivered subscription, even when the identifier is unchanged, so a subscription removed after a delivery failure can be restored. Pending registrations retain their retry backoff.

#### Parameters

| | |
|---|---|
| deviceToken | the device push token or Firebase Installation ID (FID) |
| appId | the app identifier — the Firebase `project_id` on Android, the APNS `bundle_id` on iOS |

### `PostHog.startSession`

```kotlin
fun startSession()
```

Starts a session The SDK will automatically start a session when you call setup On Android, the SDK will automatically start a session when the app is in the foreground

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

| | |
|---|---|
| isEnabled | Whether to exclude the subtree. Defaults to true; false has no effect. |

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

| Name | Summary |
|---|---|
| enabled | `val enabled: Boolean`<br />whether the feature flag is enabled |
| key | `val key: String`<br />the key of the feature flag |
| payload | `val payload: Any?`<br />the payload associated with the feature flag (if any) |
| value | `val value: Any`<br />Returns the effective value of the feature flag. For multivariate flags, returns the variant string. For boolean flags, returns the enabled boolean. |
| variant | `val variant: String?`<br />the variant of the feature flag (null for boolean flags) |

#### Functions

| Name | Summary |
|---|---|
| getPayloadAs | `inline fun <T> getPayloadAs(): T?`<br />Returns the payload deserialized as the specified type.<br /><br />`fun <T> getPayloadAs(clazz: Class<T>): T?`<br />Returns the payload deserialized as the specified class. |

### `LinkSurveyQuestion`

```kotlin
data class LinkSurveyQuestion(val link: String?) : SurveyQuestion
```

#### Properties

| Name | Summary |
|---|---|
| branching | `val branching: SurveyQuestionBranching? = null` |
| buttonText | `val buttonText: String? = null` |
| description | `val description: String? = null` |
| descriptionContentType | `val descriptionContentType: SurveyTextContentType? = null` |
| id | `val id: String? = null` |
| link | `val link: String?` |
| optional | `val optional: Boolean? = null` |
| question | `val question: String? = null` |
| skipSubmitButton | `val skipSubmitButton: Boolean? = null` |
| translations | `val translations: Map<String, SurveyQuestionTranslation>? = null` |
| type | `val type: SurveyQuestionType? = null` |

### `MultipleSurveyQuestion`

```kotlin
data class MultipleSurveyQuestion(val choices: List<String>?, val hasOpenChoice: Boolean?, val shuffleOptions: Boolean?) : SurveyQuestion
```

#### Properties

| Name | Summary |
|---|---|
| branching | `val branching: SurveyQuestionBranching? = null` |
| buttonText | `val buttonText: String? = null` |
| choices | `val choices: List<String>?` |
| description | `val description: String? = null` |
| descriptionContentType | `val descriptionContentType: SurveyTextContentType? = null` |
| hasOpenChoice | `val hasOpenChoice: Boolean?` |
| id | `val id: String? = null` |
| optional | `val optional: Boolean? = null` |
| question | `val question: String? = null` |
| shuffleOptions | `val shuffleOptions: Boolean?` |
| skipSubmitButton | `val skipSubmitButton: Boolean? = null` |
| translations | `val translations: Map<String, SurveyQuestionTranslation>? = null` |
| type | `val type: SurveyQuestionType? = null` |

### `OnPostHogSurveyClosed`

```kotlin
typealias OnPostHogSurveyClosed = (survey: PostHogDisplaySurvey) -> Unit
```

To be called when a survey is dismissed

#### Parameters

| | |
|---|---|
| survey | The survey that was closed |

### `OnPostHogSurveyResponse`

```kotlin
typealias OnPostHogSurveyResponse = (survey: PostHogDisplaySurvey, index: Int, response: PostHogSurveyResponse) -> PostHogNextSurveyQuestion?
```

To be called when a user responds to a survey question

#### Return

The next question state (next question index and completion flag)

#### Parameters

| | |
|---|---|
| survey | The current survey being displayed |
| index | The index of the question being answered |
| response | The user's response to the question |

### `OnPostHogSurveyShown`

```kotlin
typealias OnPostHogSurveyShown = (survey: PostHogDisplaySurvey) -> Unit
```

To be called when a survey is successfully shown to the user

#### Parameters

| | |
|---|---|
| survey | The survey that was displayed |

### `OpenSurveyQuestion`

```kotlin
class OpenSurveyQuestion : SurveyQuestion
```

#### Properties

| Name | Summary |
|---|---|
| branching | `val branching: SurveyQuestionBranching? = null` |
| buttonText | `val buttonText: String? = null` |
| description | `val description: String? = null` |
| descriptionContentType | `val descriptionContentType: SurveyTextContentType? = null` |
| id | `val id: String? = null` |
| optional | `val optional: Boolean? = null` |
| question | `val question: String? = null` |
| skipSubmitButton | `val skipSubmitButton: Boolean? = null` |
| translations | `val translations: Map<String, SurveyQuestionTranslation>? = null` |
| type | `val type: SurveyQuestionType? = null` |

### `PersonProfiles`

```kotlin
enum PersonProfiles : Enum<PersonProfiles> 
```

Controls when captured events should create or update PostHog person profiles.

#### Entries

| | |
|---|---|
| NEVER | `NEVER`<br />Never process person profile data. Anonymous users are not merged when they identify. |
| ALWAYS | `ALWAYS`<br />Process person profile data for all captured events. |
| IDENTIFIED_ONLY | `IDENTIFIED_ONLY`<br />Process person profile data only after `identify`, `alias`, or `group` calls. |

### `PostHogAndroidConfig`

```kotlin
class PostHogAndroidConfig @JvmOverloads constructor(projectToken: String, host: String = DEFAULT_HOST, var captureApplicationLifecycleEvents: Boolean = true, var captureDeepLinks: Boolean = true, var captureScreenViews: Boolean = true, var sessionReplayConfig: PostHogSessionReplayConfig = PostHogSessionReplayConfig(), var capturePushNotificationSubscriptions: Boolean = true, var capturePushNotificationOpened: Boolean = true) : PostHogConfig
```

Android SDK configuration.

#### Parameters

| | |
|---|---|
| projectToken | Your PostHog project token, which starts with `phc_`. Required, no default. Find it in your PostHog project settings: https://us.posthog.com/settings/project-details#variables. Leading and trailing whitespace is trimmed. Formerly named `apiKey`. Example: `PostHogAndroidConfig(projectToken = "phc_your_project_token")`. |
| host | PostHog ingestion host. Defaults to DEFAULT_HOST. |

#### Properties

| Name | Summary |
|---|---|
| captureApplicationLifecycleEvents | `var captureApplicationLifecycleEvents: Boolean`<br />Whether to capture application lifecycle events automatically, including app installed, app updated, app opened, and app backgrounded. |
| captureDeadClicks | `var captureDeadClicks: Boolean`<br />Captures `$dead_click` when a supported target has no observed meaningful UI response for three seconds. Default: false. Bounded local View/Compose change detection may transiently process unmasked UI text; raw text is not retained and neither text nor its ephemeral digest is sent. Unsupported/incomplete observations are skipped. Independent of semantic capture and replay; shares interaction exclusions. |
| captureDeepLinks | `var captureDeepLinks: Boolean`<br />Whether to capture `Deep Link Opened` events automatically. For warm links delivered to `singleTop` or `singleTask` activities, call `setIntent(intent)` in `Activity.onNewIntent` after `super.onNewIntent(intent)`. The SDK checks the current intent on resume and captures each new Intent instance, even if its URL is unchanged. Ordinary resumes do not recapture the same intent. Creation capture, including activity recreation, is unchanged. Default: `true`. |
| captureElementInteractions | `var captureElementInteractions: Boolean`<br />Captures `$autocapture` for actionable View and Compose taps. Default: false. Configure before setup. Independent of session replay; sends types and resource IDs/test tags and window-local touch coordinates in dp, never text, content descriptions or input values. Use only non-sensitive test tags. Exclude a View subtree with `com.posthog.android.R.id.posthog_autocapture_no_capture` set to true, or use postHogAutocaptureNoCapture for Compose. |
| capturePushNotificationOpened | `var capturePushNotificationOpened: Boolean`<br />Whether to auto-capture `$push_notification_opened` for cold-start taps on a tray notification (detected via the launch intent's `google.message_id` extra). A warm-start tap arrives at `Activity.onNewIntent`, which is not observable here — forward that intent to PostHogAndroid.capturePushNotificationOpened, which is deduped against this path. Foreground data messages and push delivered outside FCM need PostHog.capturePushNotificationOpened, which dedupes only notifications sent by PostHog. Also gates PostHogAndroid.capturePushNotificationOpened. Default: `true`. |
| capturePushNotificationSubscriptions | `var capturePushNotificationSubscriptions: Boolean`<br />Whether to auto-register this device's push token at startup. When enabled and Firebase Messaging is on the classpath, the SDK fetches the FCM token + Firebase `project_id` and registers them so PostHog Workflows can deliver push notifications. When the manifest enables `firebase_messaging_installation_id_enabled`, the Firebase Installation ID is registered instead. No effect when Firebase is absent (a single debug log). Instant token refresh still needs the app to forward `onNewToken` (or, in installation ID mode, `onRegistered`) to `registerPushNotificationToken(...)`. Default: `true`. |
| captureRageClicks | `var captureRageClicks: Boolean`<br />Captures `$rageclick` after four nearby taps on the same target within one second. Default: false. Independent of semantic capture and replay; shares their interaction exclusions and privacy-safe properties. |
| captureScreenViews | `var captureScreenViews: Boolean`<br />Whether to capture a `$screen` event whenever a foreground Activity starts (via `ActivityLifecycleCallbacks.onActivityStarted`). When enabled, the most recent screen name is also attached as `$screen_name` to every subsequent event captured by the SDK. To opt out of `$screen_name` stamping entirely, set this to `false` and avoid calling `PostHog.screen(...)` manually. Default: `true`. |
| sessionReplayConfig | `var sessionReplayConfig: PostHogSessionReplayConfig`<br />Android-specific session replay capture options. |

### `PostHogBeforeSend`

```kotlin
fun interface PostHogBeforeSend
```

Hook invoked before an analytics event is queued.

Return the same or a modified PostHogEvent to continue, or `null` to drop the event.

#### Functions

| Name | Summary |
|---|---|
| run | `fun run(event: PostHogEvent): PostHogEvent?`<br />Runs the hook for event. |

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

```java
config.getLogs().addBeforeSend(record -> {
    if (record.getLevel() == PostHogLogSeverity.TRACE) return null;
    return record;
});
```

#### See also

| |
|---|
| PostHogLogsConfig.addBeforeSend |
| PostHogLogsConfig.removeBeforeSend |

#### Functions

| Name | Summary |
|---|---|
| run | `fun run(record: PostHogLogRecord): PostHogLogRecord?`<br />Called once per captured record. Return the record (possibly with a mutated copy via PostHogLogRecord.copy) to keep it, or `null` to drop it. |

### `PostHogBootstrapConfig`

```kotlin
class PostHogBootstrapConfig @JvmOverloads constructor(val distinctId: String? = null, val isIdentifiedId: Boolean = false, val featureFlags: Map<String, Any>? = null, val featureFlagPayloads: Map<String, Any?>? = null)
```

Pre-seeded identity and feature-flag state applied before any network request completes: identity only on the very first SDK launch, and the feature-flag base layer on every launch until `reset()`.

Set PostHogConfig.bootstrap before calling setup to seed the distinct id and feature flag state. Events captured synchronously during initialization then carry a caller-controlled `$distinct_id` rather than the SDK-generated UUID, and feature-flag reads return caller-provided values before the first `/flags` response. Mirrors the bootstrap option in posthog-js.

Bootstrap identity applies to the very first session. An anonymous bootstrap (isIdentifiedId false) seeds the anonymous id only when none is persisted, and is otherwise ignored. An identified bootstrap (isIdentifiedId true) seeds the distinct id on a fresh install; if a local anonymous user already exists it is merged into the identified id via `identify()`, while a different already-identified user is preserved and a warning is logged. Bootstrapped feature flags are an initial snapshot, not a permanent base: they win over any persisted flag cache at setup and are served until the first `/flags` response, which then replaces them entirely — a complete response drops bootstrapped-only keys, while only a partial or errored response preserves the keys it did not recompute. `reset()` drops the snapshot.

#### Properties

| Name | Summary |
|---|---|
| distinctId | `val distinctId: String? = null`<br />The distinct id to seed on first launch. |
| featureFlagPayloads | `val featureFlagPayloads: Map<String, Any?>? = null`<br />JSON payloads paired with featureFlags, keyed by flag key. Each value is the already-decoded payload (map, list, string, number, ...). |
| featureFlags | `val featureFlags: Map<String, Any>? = null`<br />Feature flag values served until the first `/flags` response arrives, keyed by flag key. Each value is a Boolean for boolean flags or a String for multivariate flags. Only enabled values are served — `true` or a non-empty String; a `false` or empty-string entry is treated as not bootstrapped (matching posthog-js's `!!value` check) and dropped, indistinguishable from a key that was never provided. Values must be JSON-serializable; any non-serializable entry (e.g. NaN) is dropped with a logged warning rather than surfaced. |
| isIdentifiedId | `val isIdentifiedId: Boolean = false`<br />Whether distinctId represents an already-identified user. |

### `PostHogCompression`

```kotlin
enum PostHogCompression : Enum<PostHogCompression> 
```

Controls whether request bodies are compressed before being sent to the PostHog API.

#### Entries

| | |
|---|---|
| GZIP | `GZIP`<br />Gzip request bodies. |
| NONE | `NONE`<br />Send request bodies uncompressed. |

### `PostHogConfig`

```kotlin
class PostHogConfig(projectToken: String, host: String = DEFAULT_HOST, var debug: Boolean = false, var optOut: Boolean = false, var sendFeatureFlagEvent: Boolean = true, var featureFlagCalledCacheSize: Int = DEFAULT_FEATURE_FLAG_CALLED_CACHE_SIZE, var preloadFeatureFlags: Boolean = true, var evaluationContexts: List<String>? = null, var setDefaultPersonProperties: Boolean = true, var remoteConfig: Boolean = true, var flushAt: Int = DEFAULT_FLUSH_AT, var maxQueueSize: Int = DEFAULT_MAX_QUEUE_SIZE, var maxBatchSize: Int = DEFAULT_MAX_BATCH_SIZE, var maxRetries: Int = 3, var featureFlagRequestMaxRetries: Int = 1, var flushIntervalSeconds: Int = DEFAULT_FLUSH_INTERVAL_SECONDS, var encryption: PostHogEncryption? = null, var onFeatureFlags: PostHogOnFeatureFlags? = null, var sessionReplay: Boolean = false, var propertiesSanitizer: PostHogPropertiesSanitizer? = null, var getAnonymousId: (UUID) -> UUID = { it }, var reuseAnonymousId: Boolean = false, var personProfiles: PersonProfiles = PersonProfiles.IDENTIFIED_ONLY, var surveys: Boolean = false, var proxy: Proxy? = null, var surveysConfig: PostHogSurveysConfig = PostHogSurveysConfig(), var logs: PostHogLogsConfig = PostHogLogsConfig(), val remoteConfigProvider: (PostHogConfig, PostHogApi, ExecutorService, PostHogDefaultPersonPropertiesProvider?, PostHogOnRemoteConfigLoaded?, PostHogFeatureFlagCalledProvider?) -> PostHogFeatureFlagsInterface = DEFAULT_REMOTE_CONFIG_PROVIDER, val queueProvider: (PostHogConfig, PostHogApi, PostHogApiEndpoint, String?, ExecutorService) -> PostHogQueueInterface<PostHogEvent> = DEFAULT_QUEUE_PROVIDER, val errorTrackingConfig: PostHogErrorTrackingConfig = PostHogErrorTrackingConfig(), var releaseIdentifier: String? = null, var bootstrap: PostHogBootstrapConfig? = null, var pushIdentityProvider: (distinctId: String, appId: String, completion: (String?) -> Unit) -> Unit? = null)
```

The SDK Config

#### Properties

| Name | Summary |
|---|---|
| apiKey | `val apiKey: String`<br />Deprecated alias for projectToken; returns the same trimmed value. |
| beforeSendList | `val beforeSendList: List<PostHogBeforeSend>`<br />The beforeSend list |
| bootstrap | `var bootstrap: PostHogBootstrapConfig?`<br />Optional bootstrap configuration that seeds identity and feature-flag state before any network request completes. Identity is seeded only on the very first launch, when nothing has been persisted yet. The feature-flag snapshot is applied on every launch as the initial layer — served immediately and taking precedence over any persisted flag cache — until the first `/flags` response replaces it (a complete response drops bootstrapped-only keys; a partial or errored one keeps the keys it did not recompute) or `reset()` drops it. See PostHogBootstrapConfig for the precedence rules. |
| compression | `var compression: PostHogCompression`<br />How the SDK compresses request bodies before sending them to the PostHog API. |
| debug | `var debug: Boolean`<br />Logs the debug logs to the logger if enabled Defaults to false |
| encryption | `var encryption: PostHogEncryption?`<br />Hook for encrypt and decrypt events Devices are sandbox so likely not needed Defaults to no encryption |
| errorTrackingConfig | `val errorTrackingConfig: PostHogErrorTrackingConfig`<br />Configuration for PostHog Error Tracking feature. |
| evaluationContexts | `var evaluationContexts: List<String>?`<br />Optional list of evaluation context tags for feature flag evaluation When specified, only feature flags that have at least one matching evaluation tag will be evaluated Feature flags with no evaluation tags will always be evaluated for backward compatibility Example: listOf(&quot;production&quot;, &quot;web&quot;, &quot;checkout&quot;) Defaults to null (evaluate all flags) |
| evaluationEnvironments | `var evaluationEnvironments: List<String>?`<br />Optional list of evaluation environment tags for feature flag evaluation |
| featureFlagCalledCacheSize | `var featureFlagCalledCacheSize: Int`<br />Maximum number of distinct feature flag calls to cache for deduplication When the cache is full, the least recently used entries are evicted Defaults to 1000 |
| featureFlagRequestMaxRetries | `var featureFlagRequestMaxRetries: Int`<br />Maximum number of retries for feature flag requests after transient network errors. Defaults to 1. Set to 0 to disable feature flag request retries. |
| flushAt | `var flushAt: Int`<br />Number of minimum events before they are sent over the wire Defaults to 20 |
| flushIntervalSeconds | `var flushIntervalSeconds: Int`<br />Interval in seconds for sending events over the wire The lower the number, most likely more battery is used Defaults to 30s |
| getAnonymousId | `var getAnonymousId: (UUID) -> UUID`<br />Hook that allows for modification of the default mechanism for generating anonymous id (which as of now is just random UUID v7) |
| host | `val host: String`<br />The PostHog Host Defaults to https://us.i.posthog.com EU Host: https://eu.i.posthog.com |
| httpClient | `var httpClient: OkHttpClient?`<br />Optional custom OkHttpClient for HTTP requests. |
| integrations | `val integrations: List<PostHogIntegration>`<br />The integrations list |
| logs | `var logs: PostHogLogsConfig`<br />Configuration for the logs subsystem. See PostHogLogsConfig for per-field mutability semantics — resource attributes and the `flushAt` / `maxBatchSize` knobs are snapshotted at setup; the `beforeSend` chain, `maxBufferSize`, and `flushIntervalSeconds` remain live. |
| maxBatchSize | `var maxBatchSize: Int`<br />Number of maximum events in a batch call Defaults to 50 |
| maxQueueSize | `var maxQueueSize: Int`<br />Number of maximum events in memory and disk, when the maximum is exceed, the oldest event is deleted and the new one takes place Defaults to 1000 |
| maxRetries | `var maxRetries: Int`<br />Maximum number of retries for push subscription registration failures. |
| onFeatureFlags | `var onFeatureFlags: PostHogOnFeatureFlags?`<br />Hook that is called when feature flags are loaded Defaults to no callback |
| optOut | `@Volatile`<br />var optOut: Boolean<br />This flag prevents capturing any data if enabled You can overwrite this value at runtime by calling PostHog.optIn or PostHog.optOut Defaults to false |
| persistOptOut | `@Volatile`<br />var persistOptOut: Boolean<br />Whether the SDK stores the opt-out state itself and restores it on the next setup. |
| personProfiles | `var personProfiles: PersonProfiles`<br />Determines the behavior for processing user profiles. |
| preloadFeatureFlags | `var preloadFeatureFlags: Boolean`<br />Preload feature flags automatically Docs https://posthog.com/docs/feature-flags and https://posthog.com/docs/experiments Defaults to true |
| projectToken | `val projectToken: String`<br />Your PostHog project token, trimmed of leading and trailing whitespace. |
| propertiesSanitizer | `var propertiesSanitizer: PostHogPropertiesSanitizer?`<br />Hook that allows to sanitize the event properties The hook is called before the event is cached or sent over the wire |
| proxy | `var proxy: Proxy?`<br />Configures an optional HTTP proxy for the PostHog API client. |
| pushIdentityProvider | `var pushIdentityProvider: (distinctId: String, appId: String, completion: (String?) -> Unit) -> Unit?`<br />Hook that supplies a signed identity token for push subscription requests, enabling the optional identity verification of the push subscriptions API. |
| queueProvider | `val queueProvider: (PostHogConfig, PostHogApi, PostHogApiEndpoint, String?, ExecutorService) -> PostHogQueueInterface<PostHogEvent>`<br />Factory to instantiate a custom queue implementation. |
| releaseIdentifier | `var releaseIdentifier: String?`<br />The release identifier used to identify the correct proguard mappings (mapping.txt) in order to make minified stack traces into human readable stack traces |
| remoteConfig | `var remoteConfig: Boolean`<br />Preload PostHog remote config automatically Defaults to true |
| remoteConfigHolder | `var remoteConfigHolder: PostHogRemoteConfig?`<br />Reference to the PostHogRemoteConfig instance, set during setup. Used by integrations to check remote config values. |
| remoteConfigProvider | `val remoteConfigProvider: (PostHogConfig, PostHogApi, ExecutorService, PostHogDefaultPersonPropertiesProvider?, PostHogOnRemoteConfigLoaded?, PostHogFeatureFlagCalledProvider?) -> PostHogFeatureFlagsInterface`<br />Factory to instantiate a custom com.posthog.internal.PostHogRemoteConfigInterface implementation. |
| requestHeaders | `@Volatile`<br />var requestHeaders: Map&lt;String, String&gt;<br />Custom headers to send with every request to the PostHog API. |
| reuseAnonymousId | `var reuseAnonymousId: Boolean`<br />Flag to reuse the anonymous id. If enabled, the anonymous id will be reused across `identify()` and `reset()` calls. |
| sampleRateProvider | `var sampleRateProvider: () -> Double??`<br />Provider for the local session recording sample rate. Set by platform-specific modules (e.g., Android) to supply the sample rate from the session replay config without duplicating the field. |
| sendFeatureFlagEvent | `var sendFeatureFlagEvent: Boolean`<br />Send a $feature_flag_called event when a feature flag is used automatically Used by experiments |
| sessionReplay | `var sessionReplay: Boolean`<br />Enable Recording of Session Replays for Android Requires Record user sessions to be enabled in the PostHog Project Settings Defaults to false |
| setDefaultPersonProperties | `var setDefaultPersonProperties: Boolean`<br />Automatically set common device and app properties as person properties for feature flag evaluation. |
| surveys | `var surveys: Boolean`<br />Enable Surveys for Android (Intended for internal use only) |
| surveysConfig | `var surveysConfig: PostHogSurveysConfig`<br />Configuration for PostHog Surveys feature. (Intended for internal use only) |
| tracingHeaders | `var tracingHeaders: List<String>?`<br />When set, PostHog injects tracing headers into OkHttp requests whose destination hostname exactly matches one of the configured hostnames. |

#### Functions

| Name | Summary |
|---|---|
| addBeforeSend | `fun addBeforeSend(beforeSend: PostHogBeforeSend)`<br />Adds a new PostHogBeforeSend |
| addIntegration | `fun addIntegration(integration: PostHogIntegration)`<br />Adds a new integration |
| logIntegrationFailure | `fun PostHogConfig.logIntegrationFailure(integrationName: String, error: Throwable)`<br />Reports why an integration named integrationName is not available. A missing class means the host build excludes an artifact that the SDK needs, so that case becomes a warning: a debug-only message hides a whole feature, such as session replay, that stays off for the rest of the process. |
| removeBeforeSend | `fun removeBeforeSend(beforeSend: PostHogBeforeSend)`<br />Removes the PostHogBeforeSend |
| removeIntegration | `fun removeIntegration(integration: PostHogIntegration)`<br />Removes the integration |

### `PostHogDisplayChoiceQuestion`

```kotlin
class PostHogDisplayChoiceQuestion @JvmOverloads constructor(val id: String, val question: String, val questionDescription: String?, val questionDescriptionContentType: PostHogDisplaySurveyTextContentType, val isOptional: Boolean, val buttonText: String?, val choices: List<String>, val hasOpenChoice: Boolean, val shuffleOptions: Boolean, val isMultipleChoice: Boolean, val skipSubmitButton: Boolean = false) : PostHogDisplaySurveyQuestion
```

Represents a question where users can select one or more choices

#### Properties

| Name | Summary |
|---|---|
| buttonText | `val buttonText: String?`<br />Optional custom text for the question's action button |
| choices | `val choices: List<String>`<br />The list of options for the user to choose from |
| hasOpenChoice | `val hasOpenChoice: Boolean`<br />Whether the question includes an &quot;other&quot; option for users to input free-form text |
| id | `val id: String`<br />The main question id |
| isMultipleChoice | `val isMultipleChoice: Boolean`<br />Whether the user can select multiple options |
| isOptional | `val isOptional: Boolean`<br />Whether the question can be skipped |
| question | `val question: String`<br />The main question text to display |
| questionDescription | `val questionDescription: String?`<br />Optional additional description or context for the question |
| questionDescriptionContentType | `val questionDescriptionContentType: PostHogDisplaySurveyTextContentType`<br />Content type for the question description (HTML or plain text) |
| shuffleOptions | `val shuffleOptions: Boolean`<br />Whether the options should be shuffled when presented |
| skipSubmitButton | `val skipSubmitButton: Boolean = false`<br />Whether to auto-submit single choices without an open-ended option |

### `PostHogDisplayLinkQuestion`

```kotlin
class PostHogDisplayLinkQuestion(val id: String, val question: String, val questionDescription: String?, val questionDescriptionContentType: PostHogDisplaySurveyTextContentType, val isOptional: Boolean, val buttonText: String?, val link: String?) : PostHogDisplaySurveyQuestion
```

Represents a question with a clickable link

#### Properties

| Name | Summary |
|---|---|
| buttonText | `val buttonText: String?`<br />Optional custom text for the question's action button |
| id | `val id: String`<br />The main question id |
| isOptional | `val isOptional: Boolean`<br />Whether the question can be skipped |
| link | `val link: String?`<br />The URL that will be opened when the link is clicked |
| question | `val question: String`<br />The main question text to display |
| questionDescription | `val questionDescription: String?`<br />Optional additional description or context for the question |
| questionDescriptionContentType | `val questionDescriptionContentType: PostHogDisplaySurveyTextContentType`<br />Content type for the question description (HTML or plain text) |

### `PostHogDisplayOpenQuestion`

```kotlin
class PostHogDisplayOpenQuestion(val id: String, val question: String, val questionDescription: String?, val questionDescriptionContentType: PostHogDisplaySurveyTextContentType, val isOptional: Boolean, val buttonText: String?) : PostHogDisplaySurveyQuestion
```

Represents an open-ended question where users can input free-form text

#### Properties

| Name | Summary |
|---|---|
| buttonText | `val buttonText: String?`<br />Optional custom text for the question's action button |
| id | `val id: String`<br />The main question id |
| isOptional | `val isOptional: Boolean`<br />Whether the question can be skipped |
| question | `val question: String`<br />The main question text to display |
| questionDescription | `val questionDescription: String?`<br />Optional additional description or context for the question |
| questionDescriptionContentType | `val questionDescriptionContentType: PostHogDisplaySurveyTextContentType`<br />Content type for the question description (HTML or plain text) |

### `PostHogDisplayRatingQuestion`

```kotlin
class PostHogDisplayRatingQuestion @JvmOverloads constructor(val id: String, val question: String, val questionDescription: String?, val questionDescriptionContentType: PostHogDisplaySurveyTextContentType, val isOptional: Boolean, val buttonText: String?, val ratingType: PostHogDisplaySurveyRatingType, val scaleLowerBound: Int, val scaleUpperBound: Int, val lowerBoundLabel: String, val upperBoundLabel: String, val skipSubmitButton: Boolean = false) : PostHogDisplaySurveyQuestion
```

Represents a rating question where users can select a rating from a scale

#### Properties

| Name | Summary |
|---|---|
| buttonText | `val buttonText: String?`<br />Optional custom text for the question's action button |
| id | `val id: String`<br />The main question id |
| isOptional | `val isOptional: Boolean`<br />Whether the question can be skipped |
| lowerBoundLabel | `val lowerBoundLabel: String`<br />The label for the lower bound of the rating scale |
| question | `val question: String`<br />The main question text to display |
| questionDescription | `val questionDescription: String?`<br />Optional additional description or context for the question |
| questionDescriptionContentType | `val questionDescriptionContentType: PostHogDisplaySurveyTextContentType`<br />Content type for the question description (HTML or plain text) |
| ratingType | `val ratingType: PostHogDisplaySurveyRatingType`<br />The type of rating scale (numbers, emoji) |
| scaleLowerBound | `val scaleLowerBound: Int`<br />The lower bound of the rating scale |
| scaleUpperBound | `val scaleUpperBound: Int`<br />The upper bound of the rating scale |
| skipSubmitButton | `val skipSubmitButton: Boolean = false`<br />Whether selecting a rating submits without an extra submit tap |
| upperBoundLabel | `val upperBoundLabel: String`<br />The label for the upper bound of the rating scale |

### `PostHogDisplaySurvey`

```kotlin
data class PostHogDisplaySurvey(val id: String, val name: String, val questions: List<PostHogDisplaySurveyQuestion>, val appearance: PostHogDisplaySurveyAppearance? = null, val startDate: Date? = null, val endDate: Date? = null, val initialQuestionIndex: Int = 0)
```

Represents a survey that is ready to be displayed to the user. Contains all the necessary information for rendering the survey UI.

#### Properties

| Name | Summary |
|---|---|
| appearance | `val appearance: PostHogDisplaySurveyAppearance? = null`<br />The appearance configuration for the survey |
| endDate | `val endDate: Date? = null`<br />Optional date indicating when the survey should stop being shown |
| id | `val id: String`<br />The unique identifier of the survey |
| initialQuestionIndex | `val initialQuestionIndex: Int = 0`<br />The question to show when restoring unfinished progress; zero for a new survey. |
| questions | `val questions: List<PostHogDisplaySurveyQuestion>`<br />The list of questions in the survey |
| startDate | `val startDate: Date? = null`<br />Optional date indicating when the survey should start being shown |

### `PostHogDisplaySurveyAppearance`

```kotlin
data class PostHogDisplaySurveyAppearance(val fontFamily: String? = null, val backgroundColor: String? = null, val borderColor: String? = null, val submitButtonColor: String? = null, val submitButtonText: String? = null, val submitButtonTextColor: String? = null, val textColor: String? = null, val descriptionTextColor: String? = null, val ratingButtonColor: String? = null, val ratingButtonActiveColor: String? = null, val inputBackground: String? = null, val inputTextColor: String? = null, val placeholder: String? = null, val surveyPopupDelaySeconds: Double? = null, val displayThankYouMessage: Boolean = false, val thankYouMessageHeader: String? = null, val thankYouMessageDescription: String? = null, val thankYouMessageDescriptionContentType: PostHogDisplaySurveyTextContentType? = null, val thankYouMessageCloseButtonText: String? = null, val displayIntroScreen: Boolean = false, val introScreenHeader: String? = null, val introScreenDescription: String? = null, val introScreenDescriptionContentType: PostHogDisplaySurveyTextContentType? = null, val introScreenButtonText: String? = null)
```

Represents the appearance configuration for a survey.

#### Properties

| Name | Summary |
|---|---|
| backgroundColor | `val backgroundColor: String? = null`<br />Optional background color as web color (e.g. &quot;#FFFFFF&quot; or &quot;white&quot;) |
| borderColor | `val borderColor: String? = null`<br />Optional border color as web color |
| descriptionTextColor | `val descriptionTextColor: String? = null`<br />Optional color for description text as web color |
| displayIntroScreen | `val displayIntroScreen: Boolean = false`<br />Whether to show an intro screen before the first question |
| displayThankYouMessage | `val displayThankYouMessage: Boolean = false`<br />Whether to show a thank you message after survey completion |
| fontFamily | `val fontFamily: String? = null`<br />Optional font family to use throughout the survey |
| inputBackground | `val inputBackground: String? = null`<br />Optional background color for input fields as web color |
| inputTextColor | `val inputTextColor: String? = null`<br />Optional text color for input fields as web color |
| introScreenButtonText | `val introScreenButtonText: String? = null`<br />Optional custom text for the button that advances past the intro screen |
| introScreenDescription | `val introScreenDescription: String? = null`<br />Optional description text for the intro screen |
| introScreenDescriptionContentType | `val introScreenDescriptionContentType: PostHogDisplaySurveyTextContentType? = null`<br />Optional content type for the intro screen description |
| introScreenHeader | `val introScreenHeader: String? = null`<br />Optional header text for the intro screen |
| placeholder | `val placeholder: String? = null`<br />Optional placeholder text for input fields |
| ratingButtonActiveColor | `val ratingButtonActiveColor: String? = null`<br />Optional color for active/selected rating buttons as web color |
| ratingButtonColor | `val ratingButtonColor: String? = null`<br />Optional color for rating buttons as web color |
| submitButtonColor | `val submitButtonColor: String? = null`<br />Optional background color for the submit button as web color |
| submitButtonText | `val submitButtonText: String? = null`<br />Optional custom text for the submit button |
| submitButtonTextColor | `val submitButtonTextColor: String? = null`<br />Optional text color for the submit button as web color |
| surveyPopupDelaySeconds | `val surveyPopupDelaySeconds: Double? = null`<br />Optional delay, in seconds, before the survey is shown after it is triggered |
| textColor | `val textColor: String? = null`<br />Optional primary text color as web color |
| thankYouMessageCloseButtonText | `val thankYouMessageCloseButtonText: String? = null`<br />Optional custom text for the thank you message close button |
| thankYouMessageDescription | `val thankYouMessageDescription: String? = null`<br />Optional description text for the thank you message |
| thankYouMessageDescriptionContentType | `val thankYouMessageDescriptionContentType: PostHogDisplaySurveyTextContentType? = null`<br />Optional content type for the thank you message description |
| thankYouMessageHeader | `val thankYouMessageHeader: String? = null`<br />Optional header text for the thank you message |

### `PostHogDisplaySurveyQuestion`

```kotlin
class PostHogDisplaySurveyQuestion(val id: String, val question: String, val questionDescription: String?, val questionDescriptionContentType: PostHogDisplaySurveyTextContentType, val isOptional: Boolean, val buttonText: String?)
```

Base class for all survey question types

#### Properties

| Name | Summary |
|---|---|
| buttonText | `val buttonText: String?`<br />Optional custom text for the question's action button |
| id | `val id: String`<br />The main question id |
| isOptional | `val isOptional: Boolean`<br />Whether the question can be skipped |
| question | `val question: String`<br />The main question text to display |
| questionDescription | `val questionDescription: String?`<br />Optional additional description or context for the question |
| questionDescriptionContentType | `val questionDescriptionContentType: PostHogDisplaySurveyTextContentType`<br />Content type for the question description (HTML or plain text) |

### `PostHogDisplaySurveyRatingType`

```kotlin
enum PostHogDisplaySurveyRatingType : Enum<PostHogDisplaySurveyRatingType> 
```

Type of rating display for survey rating questions

#### Entries

| | |
|---|---|
| NUMBER | `NUMBER`<br />Display numeric rating options |
| EMOJI | `EMOJI`<br />Display emoji rating options |

#### Properties

| Name | Summary |
|---|---|
| value | `val value: Int` |

### `PostHogDisplaySurveyTextContentType`

```kotlin
enum PostHogDisplaySurveyTextContentType : Enum<PostHogDisplaySurveyTextContentType> 
```

Content type for text-based survey elements (e.g Question description, Thank you message description)

#### Entries

| | |
|---|---|
| HTML | `HTML`<br />Content should be rendered as HTML |
| TEXT | `TEXT`<br />Content should be rendered as plain text |

#### Properties

| Name | Summary |
|---|---|
| value | `val value: Int` |

### `PostHogEncryption`

```kotlin
interface PostHogEncryption
```

Interface for encrypt and decrypt events, by default there's no encrypt since Android is sandboxed https://source.android.com/docs/security/app-sandbox

#### Functions

| Name | Summary |
|---|---|
| decrypt | `fun decrypt(inputStream: InputStream): InputStream`<br />Decrypts data read from inputStream. |
| encrypt | `fun encrypt(outputStream: OutputStream): OutputStream`<br />Encrypts data written to outputStream. |

### `PostHogErrorTrackingAutoCaptureIntegration`

```kotlin
class PostHogErrorTrackingAutoCaptureIntegration : PostHogIntegration, Thread.UncaughtExceptionHandler
```

#### Types

| Name | Summary |
|---|---|
| CaptureTarget | `interface CaptureTarget`<br />Minimal capture surface the uncaught handler needs. Both the core client and the stateless server client can satisfy it, without sharing a public supertype. capture and flush are separate so the handler can ask for everything pending to be sent as its last act. |

#### Functions

| Name | Summary |
|---|---|
| install | `@Synchronized`<br />open override fun install(postHog: PostHogInterface)<br />Install the Integration after the SDK is setup that requires a posthog instance to capture events |
| installWith | `@Synchronized`<br />fun installWith(target: PostHogErrorTrackingAutoCaptureIntegration.CaptureTarget)<br />Installs the handler delivering captures to target. Used by SDK layers whose client is not a core PostHogInterface (e.g. the server SDK). |
| onChange | `fun onChange()`<br />Invalidates pending observations when identity, consent, screen, session or close state changes. Called synchronously on the caller's thread; calls may be nested or concurrent. This is a one-shot notification, not a pause/resume boundary. Uninstall must independently clean up. |
| onRemoteConfig | `fun onRemoteConfig(loaded: Boolean)`<br />Called when the remote config attempt for the current identity resolves. Each integration is responsible for enabling or disabling features based on the state of the remote config. |
| uncaughtException | `fun uncaughtException(thread: Thread, throwable: Throwable)` |
| uninstall | `@Synchronized`<br />open override fun uninstall()<br />Uninstall the Integration after the SDK is closed |

### `PostHogErrorTrackingConfig`

```kotlin
class PostHogErrorTrackingConfig @JvmOverloads constructor(var autoCapture: Boolean = false, val inAppIncludes: MutableList<String> = mutableListOf(), val exceptionSteps: PostHogExceptionStepsConfig = PostHogExceptionStepsConfig(), val ignoredExceptionTypes: MutableList<Class<out Throwable>> = mutableListOf())
```

Configuration for PostHog error tracking.

#### Properties

| Name | Summary |
|---|---|
| autoCapture | `var autoCapture: Boolean`<br />Enable autocapture of exceptions This feature installs an uncaught exception handler (Thread.UncaughtExceptionHandler) that will capture exceptions |
| captureNativeCrashes | `var captureNativeCrashes: Boolean`<br />Capture native (NDK) crashes from previous runs of the app. |
| exceptionSteps | `val exceptionSteps: PostHogExceptionStepsConfig`<br />Configuration for exception steps (breadcrumb-style context records attached to every captured `$exception` event as `$exception_steps`). |
| ignoredExceptionTypes | `val ignoredExceptionTypes: MutableList<Class<out Throwable>>`<br />Throwable classes to drop during capture. The throwable and every cause in its chain are matched via Class.isInstance. Also applies to `$exception` events sent through the generic capture path (matched by class name). |
| inAppExcludes | `val inAppExcludes: MutableList<String>`<br />List of package names to be excluded from inApp frames for error tracking |
| inAppIncludes | `val inAppIncludes: MutableList<String>`<br />List of package names to be considered inApp frames for error tracking |

### `PostHogEvent`

```kotlin
data class PostHogEvent(val event: String, val distinctId: String, val properties: MutableMap<String, Any>? = null, val timestamp: Date = Date(), val uuid: UUID? = TimeBasedEpochGenerator.generate(), val type: String? = null, val messageId: UUID? = null, val set: Map<String, Any>? = null, var apiKey: String? = null)
```

The PostHog event data structure accepted by the batch API

#### Properties

| Name | Summary |
|---|---|
| apiKey | `@SerializedName(value = "api_key")`<br />var apiKey: String? |
| distinctId | `@SerializedName(value = "distinct_id")`<br />val distinctId: String<br />The distinct Id |
| event | `val event: String`<br />The event name |
| messageId | `@SerializedName(value = "message_id")`<br />val ~~messageId~~: UUID? = null |
| properties | `val properties: MutableMap<String, Any>? = null`<br />All the event properties |
| set | `@SerializedName(value = "$set")`<br />val ~~set~~: Map&lt;String, Any&gt;? = null |
| timestamp | `val timestamp: Date`<br />The event timestamp. UTC is preferred; the equivalent instant is serialized in UTC |
| type | `val type: String? = null` |
| uuid | `val uuid: UUID?`<br />the UUID v4 is automatically generated and used for deduplication |

#### Functions

| Name | Summary |
|---|---|
| isExceptionEvent | `fun isExceptionEvent(): Boolean`<br />Checks if the event is an exception event ($exception) |
| isFatalExceptionEvent | `fun isFatalExceptionEvent(): Boolean`<br />Checks if the event is a fatal exception event ($exception) and properties ($exception_level=fatal) |

### `PostHogEventName`

```kotlin
enum PostHogEventName : Enum<PostHogEventName>
```

#### Entries

| | |
|---|---|
| SNAPSHOT | `SNAPSHOT`<br />Session replay snapshot event. |
| SET | `SET`<br />Sets person properties. |
| IDENTIFY | `IDENTIFY`<br />Identifies a user. |
| SCREEN | `SCREEN`<br />A screen view. |
| GROUP_IDENTIFY | `GROUP_IDENTIFY`<br />Sets group properties. |
| CREATE_ALIAS | `CREATE_ALIAS`<br />Aliases one distinct ID to another. |
| FEATURE_FLAG_CALLED | `FEATURE_FLAG_CALLED`<br />A feature flag was evaluated. |
| FEATURE_VIEW | `FEATURE_VIEW`<br />A feature was viewed. |
| FEATURE_INTERACTION | `FEATURE_INTERACTION`<br />A feature was interacted with. |
| EXCEPTION | `EXCEPTION`<br />A captured exception. |

#### Properties

| Name | Summary |
|---|---|
| event | `val event: String`<br />The event name sent to PostHog. |

### `PostHogExceptionStepsConfig`

```kotlin
class PostHogExceptionStepsConfig @JvmOverloads constructor(var enabled: Boolean = true, var maxBytes: Int = 32768)
```

Configuration for exception steps: breadcrumb-style context records that are attached to every captured `$exception` event as `$exception_steps`.

Record steps with com.posthog.PostHog.addExceptionStep.

#### Properties

| Name | Summary |
|---|---|
| enabled | `var enabled: Boolean`<br />Enable recording and attaching exception steps. |
| maxBytes | `var maxBytes: Int`<br />Total UTF-8 byte budget for the rolling step buffer. When adding a step would exceed the budget, the oldest steps are evicted until the total fits. A single step larger than the budget is rejected outright. |

### `PostHogIntegration`

```kotlin
interface PostHogIntegration
```

Integration interface for capturing events automatically or adding plugins to the PostHog SDK

#### Functions

| Name | Summary |
|---|---|
| install | `fun install(postHog: PostHogInterface)`<br />Install the Integration after the SDK is setup that requires a posthog instance to capture events |
| onChange | `fun onChange()`<br />Invalidates pending observations when identity, consent, screen, session or close state changes. Called synchronously on the caller's thread; calls may be nested or concurrent. This is a one-shot notification, not a pause/resume boundary. Uninstall must independently clean up. |
| onRemoteConfig | `fun onRemoteConfig(loaded: Boolean = true)`<br />Called when the remote config attempt for the current identity resolves. Each integration is responsible for enabling or disabling features based on the state of the remote config. |
| uninstall | `fun uninstall()`<br />Uninstall the Integration after the SDK is closed |

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

| Name | Summary |
|---|---|
| appState | `val appState: String? = null`<br />`"foreground"` or `"background"` at capture time. |
| attributes | `val attributes: Map<String, Any>`<br />Structured per-record attributes attached by the caller. |
| body | `val body: String`<br />Log message body. Blank records are dropped after `beforeSend`. |
| distinctId | `val distinctId: String? = null`<br />Snapshot of the active PostHog distinct id at capture time. |
| featureFlagKeys | `val featureFlagKeys: List<String>`<br />Snapshot of feature flag keys that evaluated to active (truthy boolean or any multivariant value) at capture time. Use this to filter logs by flag state in the PostHog UI. |
| level | `val level: PostHogLogSeverity`<br />Severity; defaults to PostHogLogSeverity.INFO. |
| observedTimeUnixNano | `val observedTimeUnixNano: String`<br />Advanced/observability field. Defaults to timeUnixNano; differs only when the SDK observes a record after the original timestamp (e.g. replay from persisted storage). |
| screenName | `val screenName: String? = null`<br />Last screen name reported via com.posthog.PostHog.screen. |
| sessionId | `val sessionId: String? = null`<br />Snapshot of the active session id at capture time. |
| spanId | `val spanId: String? = null`<br />W3C span id paired with traceId. |
| timeUnixNano | `val timeUnixNano: String`<br />Advanced/observability field. Capture time as nanoseconds since epoch, encoded as a string to avoid i64 overflow on the JSON wire. |
| traceFlags | `val traceFlags: Int? = null`<br />W3C trace flags byte (e.g. `0x01` for sampled). |
| traceId | `val traceId: String? = null`<br />W3C trace id, when capture is part of a distributed trace. |

### `PostHogLogSeverity`

```kotlin
enum PostHogLogSeverity : Enum<PostHogLogSeverity> 
```

Severity level for a captured log record.

Constants are declared in severity order so `>=` comparisons on severityNumber give the expected result — for example, &quot;warn and worse&quot; is `severity.severityNumber >= PostHogLogSeverity.WARN.severityNumber`.

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

#### Entries

| | |
|---|---|
| TRACE | `TRACE`<br />Fine-grained tracing detail. |
| DEBUG | `DEBUG`<br />Debugging detail. |
| INFO | `INFO`<br />Normal operation. |
| WARN | `WARN`<br />Something unexpected that is not an error. |
| ERROR | `ERROR`<br />An error. |
| FATAL | `FATAL`<br />An unrecoverable error. |

#### Properties

| Name | Summary |
|---|---|
| severityNumber | `val severityNumber: Int`<br />OTLP `severityNumber` (1, 5, 9, 13, 17, 21). |
| severityText | `val severityText: String`<br />OTLP `severityText` (lower-case). |

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

```java
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

| |
|---|
| PostHogLogSeverity |
| PostHogLogsConfig |
| PostHogBeforeSendLog |

#### Functions

| Name | Summary |
|---|---|
| debug | `fun debug(message: String, attributes: Map<String, Any>? = null)`<br />Captures a debug-level record. Diagnostic detail useful during development. |
| error | `fun error(message: String, attributes: Map<String, Any>? = null)`<br />Captures an error-level record. An operation failed; the app may continue. |
| fatal | `fun fatal(message: String, attributes: Map<String, Any>? = null)`<br />Captures a fatal-level record. An unrecoverable failure; the app likely cannot continue. Logged records still ship asynchronously, so consider calling com.posthog.PostHog.flush before the process exits. |
| info | `fun info(message: String, attributes: Map<String, Any>? = null)`<br />Captures an info-level record. Default level for regular runtime events. |
| log | `fun log(message: String, severity: PostHogLogSeverity = PostHogLogSeverity.INFO, attributes: Map<String, Any>? = null)`<br />Captures a log record at the given severity. |
| trace | `fun trace(message: String, attributes: Map<String, Any>? = null)`<br />Captures a finest-grained trace-level record. Usually only enabled while diagnosing — high-volume; will trip the PostHogLogsConfig.rateCapMaxLogs cap quickly in production. |
| warn | `fun warn(message: String, attributes: Map<String, Any>? = null)`<br />Captures a warn-level record. Something unexpected happened but the operation continued. |

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

```java
PostHogAndroidConfig config = new PostHogAndroidConfig(projectToken);
config.getLogs().setServiceName("checkout-android");
config.getLogs().setEnvironment("production");
config.getLogs().addBeforeSend(record ->
    record.getBody().contains("@") ? null : record
);
PostHogAndroid.setup(context, config);
```

#### Properties

| Name | Summary |
|---|---|
| beforeSendList | `val beforeSendList: List<PostHogBeforeSendLog>`<br />Snapshot of the currently registered `beforeSend` hooks, in registration order. Useful for diagnostics and test assertions; the returned list is an immutable copy. |
| environment | `var environment: String?`<br />Environment the app is running in. Lets you separate production logs from staging/development noise in the PostHog UI. Common values: `"production"`, `"staging"`, `"development"`. Omitted from the payload entirely when `null`. |
| flushAt | `var flushAt: Int`<br />Threshold at which the queue triggers a flush automatically. |
| flushIntervalSeconds | `var flushIntervalSeconds: Int`<br />How often the queue checks for records to flush, in seconds. |
| maxBatchSize | `var maxBatchSize: Int`<br />Initial maximum number of records sent in a single request. |
| maxBufferSize | `var maxBufferSize: Int`<br />Maximum number of records held on disk before the oldest is dropped (FIFO). |
| rateCapMaxLogs | `var rateCapMaxLogs: Int`<br />Maximum number of log records accepted per rateCapWindowSeconds before subsequent records in the same window are dropped at capture time. |
| rateCapWindowSeconds | `var rateCapWindowSeconds: Int`<br />Tumbling window in seconds used by rateCapMaxLogs. The counter resets when wall-clock time advances past the window. Set to `0` (or any non-positive value) to disable the cap entirely. |
| resourceAttributes | `var resourceAttributes: Map<String, Any>`<br />Additional metadata attached to every batch of records — anything you'd want to filter or segment by in the PostHog UI that isn't record-specific (e.g. region, build flavor, device model). |
| serviceName | `var serviceName: String?`<br />Name of the application sending logs. Used to group records in the PostHog UI when multiple apps report to the same project, and to match values used by other observability tooling. |
| serviceVersion | `var serviceVersion: String?`<br />Version of the application sending logs, typically your release version string. Lets you filter and segment log records by app version in the PostHog UI. |

#### Functions

| Name | Summary |
|---|---|
| addBeforeSend | `fun addBeforeSend(hook: PostHogBeforeSendLog)`<br />Adds a `beforeSend` hook that can mutate or drop log records before they reach the queue. |
| removeBeforeSend | `fun removeBeforeSend(hook: PostHogBeforeSendLog)`<br />Removes a previously-added `beforeSend` hook. Pass the same lambda reference you originally registered. |

### `PostHogMaskModifier`

```kotlin
object PostHogMaskModifier
```

Jetpack Compose modifiers for controlling masking in Android session replay captures.

#### Functions

| Name | Summary |
|---|---|
| postHogMask | `fun Modifier.postHogMask(isEnabled: Boolean = true): Modifier`<br />Modifier to mask elements in the session replay. |
| postHogUnmask | `fun Modifier.postHogUnmask(isEnabled: Boolean = true): Modifier`<br />Modifier to explicitly unmask elements in the session replay. |

### `PostHogNextSurveyQuestion`

```kotlin
class PostHogNextSurveyQuestion(val questionIndex: Int, val isSurveyCompleted: Boolean = false)
```

Represents the next state after a user responds to a survey question.

#### Properties

| Name | Summary |
|---|---|
| isSurveyCompleted | `val isSurveyCompleted: Boolean = false`<br />Whether all questions have been answered and the survey is complete. Depending on the survey appearance configuration, you may want to show the &quot;Thank you&quot; message or dismiss the survey at this point |
| questionIndex | `val questionIndex: Int`<br />The index of the next question to be displayed (0-based) |

### `PostHogOkHttpInterceptor`

```kotlin
class PostHogOkHttpInterceptor(captureNetworkTelemetry: Boolean = true, postHog: PostHogInterface? = null) : Interceptor
```

Interceptor that captures network telemetry for session replay and, when PostHogConfig.tracingHeaders is configured, injects PostHog tracing headers into matching OkHttp requests.

Install this interceptor on each okhttp3.OkHttpClient whose requests should be captured or annotated with tracing headers.

#### Parameters

| | |
|---|---|
| captureNetworkTelemetry | Whether to capture network timing telemetry for session replay. |
| postHog | Optional SDK instance to use; when omitted, the global PostHog singleton is used. |

#### Functions

| Name | Summary |
|---|---|
| intercept | `fun intercept(chain: Interceptor.Chain): Response` |

### `PostHogOnFeatureFlags`

```kotlin
fun interface PostHogOnFeatureFlags
```

Interface for being notified once feature flags has been loaded

#### Functions

| Name | Summary |
|---|---|
| loaded | `fun loaded()`<br />The method that is called when feature flags are loaded |

### `PostHogPropertiesSanitizer`

```kotlin
fun interface PostHogPropertiesSanitizer
```

Hook to sanitize the event properties

#### Functions

| Name | Summary |
|---|---|
| sanitize | `fun sanitize(properties: MutableMap<String, Any>): Map<String, Any>`<br />Sanitizes the event properties |

### `PostHogScreenshotColorMode`

```kotlin
enum PostHogScreenshotColorMode : Enum<PostHogScreenshotColorMode> 
```

Pixel format for session replay screenshot captures, independent of resolution and compression.

#### Entries

| | |
|---|---|
| ARGB_8888 | `ARGB_8888`<br />Four bytes per pixel, preserving alpha and eight bits per color channel before encoding. |
| RGB_565 | `RGB_565`<br />Two bytes per pixel, with reduced color precision and no alpha channel. |

### `PostHogSessionReplayConfig`

```kotlin
class PostHogSessionReplayConfig @JvmOverloads constructor(var maskAllTextInputs: Boolean = true, var maskAllImages: Boolean = true, var captureLogcat: Boolean = true, var drawableConverter: PostHogDrawableConverter? = null, var screenshot: Boolean = false, var debouncerDelayMs: Long = 1000, var throttleDelayMs: Long = 1000, var sampleRate: Double? = null)
```

Android session replay capture options.

Use this as `PostHogAndroidConfig.sessionReplayConfig` when enabling session replay through `PostHogConfig.sessionReplay`.

#### Properties

| Name | Summary |
|---|---|
| captureLogcat | `var captureLogcat: Boolean`<br />Enable capturing of logcat as console events Defaults to true |
| captureTouches | `var captureTouches: Boolean`<br />Capture touch coordinates in session replay. Defaults to true. Set before SDK setup. Runtime changes are not supported. Screenshot and view capture are unaffected. Touches inside views marked `ph-no-capture` or `postHogMask()` nodes are never recorded. Disable this when other touch positions could reveal sensitive input, such as taps on fields masked only by `maskAllTextInputs`. |
| debouncerDelayMs | `var debouncerDelayMs: Long`<br />Debouncer delay used to reduce the number of snapshots captured and reduce performance impact. This is used for capturing the view as a wireframe or screenshot. The lower the number, the more snapshots are captured and the higher the performance impact. Defaults to 1000ms = 1s. Ps: it was 500ms by default until version 3.8.2. |
| drawableConverter | `var drawableConverter: PostHogDrawableConverter?`<br />Converts custom Drawable to Bitmap By default PostHog tries to convert the Drawable to Bitmap, the supported types are BitmapDrawable, ColorDrawable, GradientDrawable, InsetDrawable, LayerDrawable, RippleDrawable |
| maskAllImages | `var maskAllImages: Boolean`<br />Enable masking of all images to a placeholder Defaults to true |
| maskAllTextInputs | `var maskAllTextInputs: Boolean`<br />Enable masking of all text and text input fields Defaults to true |
| sampleRate | `var sampleRate: Double?`<br />Local sample rate for session recording, a value between 0.0 and 1.0. When set, this takes precedence over the remote config sample rate. `null` means no local override, so the SDK uses the remote config value. Defaults to `null`. |
| screenshot | `var screenshot: Boolean`<br />By default Session replay will capture all the views on the screen as a wireframe, By enabling this option, PostHog will capture the screenshot of the screen. The screenshot may contain sensitive information, use with caution. In both modes, windows that set `FLAG_SECURE` are recorded as a solid black frame, and their touches are not recorded. |
| screenshotColorMode | `@Volatile`<br />var screenshotColorMode: PostHogScreenshotColorMode<br />Pixel format used for screenshot captures. Defaults to PostHogScreenshotColorMode.ARGB_8888 to preserve alpha and color precision before lossy WebP compression. PostHogScreenshotColorMode.RGB_565 uses less bitmap memory but reduces color precision and removes alpha, making transparent activity window regions appear black. In composed scenes, dialogs use ARGB_8888 to preserve transparency. Devices that reject RGB_565 fall back to ARGB_8888. Does not enable screenshot capture. |
| screenshotCompressionQuality | `@Volatile`<br />var screenshotCompressionQuality: Int<br />WebP compression quality for screenshots, clamped to 0..100. Defaults to 30. Higher values generally retain more detail and produce larger payloads. Compression is lossy, except on Android 10 (API 29), where the platform's legacy WebP encoder uses lossless compression at quality 100. Does not change screenshot resolution or enable screenshot capture. |
| screenshotScale | `@Volatile`<br />var screenshotScale: Float?<br />Multiplier for the physical width and height of screenshot captures, clamped to 0.1..1.0. `null` (default) captures one pixel per dp (1/density), so screens with a density of 1 or less capture at full resolution. For example, 0.5 captures half the width and height. Dimensions are rounded up to at least one pixel; the logical replay viewport is unchanged. NaN and infinite values reset the scale to `null`. Does not change compression quality, color mode, or enable screenshot capture. |
| throttleDelayMs | `var throttleDelayMs: Long`<br />Throttling delay used to reduce the number of snapshots captured and reduce performance impact. This is used for capturing the view as a wireframe or screenshot. The lower the number, the more snapshots are captured and the higher the performance impact. Defaults to 1000ms = 1s. |
| verifyScreenshotMaskAlignment | `var verifyScreenshotMaskAlignment: Boolean`<br />Verifies mask alignment for session replay screenshots. This can preserve screenshots during pixel-only redraws, including continuously animated content, but performs additional view hierarchy walks while a screenshot is captured. Defaults to false. Windows with a Compose root always use the verified path regardless of this flag, so setting it to false does not disable verification for them. |

### `PostHogSurveyResponse`

```kotlin
sealed class PostHogSurveyResponse
```

Represents a user's response to a survey question.

#### Types

| Name | Summary |
|---|---|
| Link | `data class Link(val clicked: Boolean) : PostHogSurveyResponse`<br />Response for a link question. |
| MultipleChoice | `data class MultipleChoice(val selectedChoices: List<String>?) : PostHogSurveyResponse`<br />Response for a multiple choice question. |
| Rating | `data class Rating(val rating: Int?) : PostHogSurveyResponse`<br />Response for a rating question. |
| SingleChoice | `data class SingleChoice(val selectedChoice: String?) : PostHogSurveyResponse`<br />Response for a single choice question. |
| Text | `data class Text(val text: String?) : PostHogSurveyResponse`<br />Response for an open text question. |

#### Functions

| Name | Summary |
|---|---|
| toResponseValue | `fun toResponseValue(): Any?`<br />Creates a response value for sending to the PostHog API. |

### `PostHogSurveysConfig`

```kotlin
class PostHogSurveysConfig
```

Configuration for PostHog Surveys feature.

#### Properties

| Name | Summary |
|---|---|
| overrideDisplayLanguage | `var overrideDisplayLanguage: String?`<br />Optional explicit override for the language used when rendering surveys. |
| surveysDelegate | `var surveysDelegate: PostHogSurveysDelegate`<br />Delegate responsible for managing survey presentation in your app. Handles survey rendering, response collection, and lifecycle events. You can provide your own delegate for a custom survey presentation. |

### `PostHogSurveysDefaultDelegate`

```kotlin
class PostHogSurveysDefaultDelegate(config: PostHogConfig? = null) : PostHogSurveysDelegate
```

Default implementation of PostHogSurveysDelegate. This implementation doesn't render any UI, it only logs when survey methods are called.

Users should implement their own delegate to handle survey rendering.

#### Functions

| Name | Summary |
|---|---|
| cleanupSurveys | `fun cleanupSurveys()`<br />Called when surveys should be cleaned up. This default implementation only logs that cleanup was requested. |
| renderSurvey | `fun renderSurvey(survey: PostHogDisplaySurvey, onSurveyShown: OnPostHogSurveyShown, onSurveyResponse: OnPostHogSurveyResponse, onSurveyClosed: OnPostHogSurveyClosed)`<br />Called when a survey should be rendered. This default implementation only logs that a survey was requested to be shown. |

### `PostHogSurveysDelegate`

```kotlin
interface PostHogSurveysDelegate
```

Delegate responsible for managing survey presentation in your app. Handles survey rendering, response collection, and lifecycle events. You can provide your own delegate for a custom survey presentation.

#### Functions

| Name | Summary |
|---|---|
| cleanupSurveys | `fun cleanupSurveys()`<br />Called when surveys are stopped to clean up any UI elements and reset the survey display state. This method should handle the dismissal of any active surveys and cleanup of associated resources. |
| renderSurvey | `fun renderSurvey(survey: PostHogDisplaySurvey, onSurveyShown: OnPostHogSurveyShown, onSurveyResponse: OnPostHogSurveyResponse, onSurveyClosed: OnPostHogSurveyClosed)`<br />Called when an activated PostHog survey needs to be rendered on the app's UI |

### `PostHogSurveysResumeAwareDelegate`

```kotlin
interface PostHogSurveysResumeAwareDelegate : PostHogSurveysDelegate
```

Optional capability for delegates that restore unfinished surveys.

Delegates without this capability, or returning false, start fresh at question 0. Opting in requires starting at PostHogDisplaySurvey.initialQuestionIndex; the SDK restores saved answers and the submission ID for that attempt.

For example, a renderer that honors the initial question can declare: `override val supportsSurveyResume: Boolean = true`.

#### Properties

| Name | Summary |
|---|---|
| supportsSurveyResume | `val supportsSurveyResume: Boolean`<br />Whether this delegate honors PostHogDisplaySurvey.initialQuestionIndex, so the SDK resumes surveys. |

#### Functions

| Name | Summary |
|---|---|
| cleanupSurveys | `fun cleanupSurveys()`<br />Called when surveys are stopped to clean up any UI elements and reset the survey display state. This method should handle the dismissal of any active surveys and cleanup of associated resources. |
| renderSurvey | `fun renderSurvey(survey: PostHogDisplaySurvey, onSurveyShown: OnPostHogSurveyShown, onSurveyResponse: OnPostHogSurveyResponse, onSurveyClosed: OnPostHogSurveyClosed)`<br />Called when an activated PostHog survey needs to be rendered on the app's UI |

### `RatingSurveyQuestion`

```kotlin
class RatingSurveyQuestion(val display: SurveyRatingDisplayType?, val scale: Int?, val lowerBoundLabel: String?, val upperBoundLabel: String?) : SurveyQuestion
```

#### Properties

| Name | Summary |
|---|---|
| branching | `val branching: SurveyQuestionBranching? = null` |
| buttonText | `val buttonText: String? = null` |
| description | `val description: String? = null` |
| descriptionContentType | `val descriptionContentType: SurveyTextContentType? = null` |
| display | `val display: SurveyRatingDisplayType?` |
| id | `val id: String? = null` |
| lowerBoundLabel | `val lowerBoundLabel: String?` |
| optional | `val optional: Boolean? = null` |
| question | `val question: String? = null` |
| scale | `val scale: Int?` |
| skipSubmitButton | `val skipSubmitButton: Boolean? = null` |
| translations | `val translations: Map<String, SurveyQuestionTranslation>? = null` |
| type | `val type: SurveyQuestionType? = null` |
| upperBoundLabel | `val upperBoundLabel: String?` |

### `SingleSurveyQuestion`

```kotlin
data class SingleSurveyQuestion(val choices: List<String>?, val hasOpenChoice: Boolean?, val shuffleOptions: Boolean?) : SurveyQuestion
```

#### Properties

| Name | Summary |
|---|---|
| branching | `val branching: SurveyQuestionBranching? = null` |
| buttonText | `val buttonText: String? = null` |
| choices | `val choices: List<String>?` |
| description | `val description: String? = null` |
| descriptionContentType | `val descriptionContentType: SurveyTextContentType? = null` |
| hasOpenChoice | `val hasOpenChoice: Boolean?` |
| id | `val id: String? = null` |
| optional | `val optional: Boolean? = null` |
| question | `val question: String? = null` |
| shuffleOptions | `val shuffleOptions: Boolean?` |
| skipSubmitButton | `val skipSubmitButton: Boolean? = null` |
| translations | `val translations: Map<String, SurveyQuestionTranslation>? = null` |
| type | `val type: SurveyQuestionType? = null` |

### `Survey`

```kotlin
data class Survey(val id: String, val name: String, val type: SurveyType, val questions: List<SurveyQuestion>, val description: String?, val featureFlagKeys: List<SurveyFeatureFlagKeyValue>?, val linkedFlagKey: String?, val targetingFlagKey: String?, val internalTargetingFlagKey: String?, val conditions: SurveyConditions?, val appearance: SurveyAppearance?, val currentIteration: Int?, val currentIterationStartDate: Date?, val startDate: Date?, val endDate: Date?, val schedule: SurveySchedule?, val translations: Map<String, SurveyTranslation>? = null, val enablePartialResponses: Boolean? = null)
```

#### Properties

| Name | Summary |
|---|---|
| appearance | `val appearance: SurveyAppearance?` |
| conditions | `val conditions: SurveyConditions?` |
| currentIteration | `@SerializedName(value = "current_iteration")`<br />val currentIteration: Int? |
| currentIterationStartDate | `@SerializedName(value = "current_iteration_start_date")`<br />val currentIterationStartDate: Date? |
| description | `val description: String?` |
| enablePartialResponses | `@SerializedName(value = "enable_partial_responses")`<br />val enablePartialResponses: Boolean? = null |
| endDate | `@SerializedName(value = "end_date")`<br />val endDate: Date? |
| featureFlagKeys | `@SerializedName(value = "feature_flag_keys")`<br />val featureFlagKeys: List&lt;SurveyFeatureFlagKeyValue&gt;? |
| id | `val id: String` |
| internalTargetingFlagKey | `@SerializedName(value = "internal_targeting_flag_key")`<br />val internalTargetingFlagKey: String? |
| linkedFlagKey | `@SerializedName(value = "linked_flag_key")`<br />val linkedFlagKey: String? |
| questions | `val questions: List<SurveyQuestion>` |
| schedule | `val schedule: SurveySchedule?` |
| startDate | `@SerializedName(value = "start_date")`<br />val startDate: Date? |
| targetingFlagKey | `@SerializedName(value = "targeting_flag_key")`<br />val targetingFlagKey: String? |
| translations | `val translations: Map<String, SurveyTranslation>? = null` |
| type | `val type: SurveyType` |

### `SurveyAppearance`

```kotlin
data class SurveyAppearance(val position: SurveyAppearancePosition?, val fontFamily: String?, val backgroundColor: String?, val submitButtonColor: String?, val submitButtonText: String?, val submitButtonTextColor: String?, val textColor: String?, val descriptionTextColor: String?, val ratingButtonColor: String?, val ratingButtonActiveColor: String?, val ratingButtonHoverColor: String?, val inputBackground: String?, val inputTextColor: String?, val whiteLabel: Boolean?, val autoDisappear: Boolean?, val displayThankYouMessage: Boolean?, val thankYouMessageHeader: String?, val thankYouMessageDescription: String?, val thankYouMessageDescriptionContentType: SurveyTextContentType?, val thankYouMessageCloseButtonText: String?, val borderColor: String, val placeholder: String?, val shuffleQuestions: Boolean?, val surveyPopupDelaySeconds: Double?, val widgetType: SurveyAppearanceWidgetType?, val widgetSelector: String?, val widgetLabel: String?, val widgetColor: String?, val displayIntroScreen: Boolean? = null, val introScreenHeader: String? = null, val introScreenDescription: String? = null, val introScreenDescriptionContentType: SurveyTextContentType? = null, val introScreenButtonText: String? = null)
```

#### Properties

| Name | Summary |
|---|---|
| autoDisappear | `val autoDisappear: Boolean?` |
| backgroundColor | `val backgroundColor: String?` |
| borderColor | `val borderColor: String` |
| descriptionTextColor | `val descriptionTextColor: String?` |
| displayIntroScreen | `val displayIntroScreen: Boolean? = null` |
| displayThankYouMessage | `val displayThankYouMessage: Boolean?` |
| fontFamily | `val fontFamily: String?` |
| inputBackground | `val inputBackground: String?` |
| inputTextColor | `val inputTextColor: String?` |
| introScreenButtonText | `val introScreenButtonText: String? = null` |
| introScreenDescription | `val introScreenDescription: String? = null` |
| introScreenDescriptionContentType | `val introScreenDescriptionContentType: SurveyTextContentType? = null` |
| introScreenHeader | `val introScreenHeader: String? = null` |
| placeholder | `val placeholder: String?` |
| position | `val position: SurveyAppearancePosition?` |
| ratingButtonActiveColor | `val ratingButtonActiveColor: String?` |
| ratingButtonColor | `val ratingButtonColor: String?` |
| ratingButtonHoverColor | `val ratingButtonHoverColor: String?` |
| shuffleQuestions | `val shuffleQuestions: Boolean?` |
| submitButtonColor | `val submitButtonColor: String?` |
| submitButtonText | `val submitButtonText: String?` |
| submitButtonTextColor | `val submitButtonTextColor: String?` |
| surveyPopupDelaySeconds | `val surveyPopupDelaySeconds: Double?` |
| textColor | `val textColor: String?` |
| thankYouMessageCloseButtonText | `val thankYouMessageCloseButtonText: String?` |
| thankYouMessageDescription | `val thankYouMessageDescription: String?` |
| thankYouMessageDescriptionContentType | `val thankYouMessageDescriptionContentType: SurveyTextContentType?` |
| thankYouMessageHeader | `val thankYouMessageHeader: String?` |
| whiteLabel | `val whiteLabel: Boolean?` |
| widgetColor | `val widgetColor: String?` |
| widgetLabel | `val widgetLabel: String?` |
| widgetSelector | `val widgetSelector: String?` |
| widgetType | `val widgetType: SurveyAppearanceWidgetType?` |

### `SurveyAppearancePosition`

```kotlin
enum SurveyAppearancePosition : Enum<SurveyAppearancePosition>
```

#### Entries

| | |
|---|---|
| LEFT | `LEFT` |
| RIGHT | `RIGHT` |
| CENTER | `CENTER` |

#### Properties

| Name | Summary |
|---|---|
| value | `val value: String` |

### `SurveyAppearanceWidgetType`

```kotlin
enum SurveyAppearanceWidgetType : Enum<SurveyAppearanceWidgetType>
```

#### Entries

| | |
|---|---|
| BUTTON | `BUTTON` |
| TAB | `TAB` |
| SELECTOR | `SELECTOR` |

#### Properties

| Name | Summary |
|---|---|
| value | `val value: String` |

### `SurveyConditions`

```kotlin
data class SurveyConditions @JvmOverloads constructor(val url: String?, val urlMatchType: SurveyMatchType?, val selector: String?, val deviceTypes: List<String>?, val deviceTypesMatchType: SurveyMatchType?, val seenSurveyWaitPeriodInDays: Int?, val events: SurveyEventConditions?, val linkedFlagVariant: String? = null)
```

#### Properties

| Name | Summary |
|---|---|
| deviceTypes | `val deviceTypes: List<String>?` |
| deviceTypesMatchType | `val deviceTypesMatchType: SurveyMatchType?` |
| events | `val events: SurveyEventConditions?` |
| linkedFlagVariant | `val linkedFlagVariant: String? = null`<br />Required variant of the linked flag; &quot;any&quot; accepts any enabled value. |
| seenSurveyWaitPeriodInDays | `val seenSurveyWaitPeriodInDays: Int?` |
| selector | `val selector: String?` |
| url | `val url: String?` |
| urlMatchType | `val urlMatchType: SurveyMatchType?` |

### `SurveyEventCondition`

```kotlin
data class SurveyEventCondition(val name: String, val propertyFilters: Map<String, SurveyPropertyFilter>? = null)
```

#### Properties

| Name | Summary |
|---|---|
| propertyFilters | `val propertyFilters: Map<String, SurveyPropertyFilter>? = null` |

### `SurveyEventConditions`

```kotlin
data class SurveyEventConditions(val repeatedActivation: Boolean?, val values: List<SurveyEventCondition>)
```

#### Properties

| Name | Summary |
|---|---|
| repeatedActivation | `val repeatedActivation: Boolean?` |

### `SurveyFeatureFlagKeyValue`

```kotlin
data class SurveyFeatureFlagKeyValue(val key: String, val value: String?)
```

#### Properties

| Name | Summary |
|---|---|
| key | `val key: String` |
| value | `val value: String?` |

### `SurveyMatchType`

```kotlin
enum SurveyMatchType : Enum<SurveyMatchType>
```

#### Entries

| | |
|---|---|
| REGEX | `REGEX` |
| NOT_REGEX | `NOT_REGEX` |
| EXACT | `EXACT` |
| IS_NOT | `IS_NOT` |
| I_CONTAINS | `I_CONTAINS` |
| NOT_I_CONTAINS | `NOT_I_CONTAINS` |
| GT | `GT` |
| LT | `LT` |

#### Properties

| Name | Summary |
|---|---|
| value | `val value: String` |

### `SurveyPropertyFilter`

```kotlin
class SurveyPropertyFilter(val values: List<String>, val operator: SurveyMatchType)
```

#### Properties

| Name | Summary |
|---|---|
| operator | `val operator: SurveyMatchType` |

### `SurveyQuestion`

```kotlin
class SurveyQuestion
```

#### Properties

| Name | Summary |
|---|---|
| branching | `val branching: SurveyQuestionBranching? = null` |
| buttonText | `val buttonText: String? = null` |
| description | `val description: String? = null` |
| descriptionContentType | `val descriptionContentType: SurveyTextContentType? = null` |
| id | `val id: String? = null` |
| optional | `val optional: Boolean? = null` |
| question | `val question: String? = null` |
| skipSubmitButton | `val skipSubmitButton: Boolean? = null` |
| translations | `val translations: Map<String, SurveyQuestionTranslation>? = null` |
| type | `val type: SurveyQuestionType? = null` |

### `SurveyQuestionBranching`

```kotlin
sealed class SurveyQuestionBranching
```

#### Types

| Name | Summary |
|---|---|
| End | `object End : SurveyQuestionBranching` |
| Next | `object Next : SurveyQuestionBranching` |
| ResponseBased | `class ResponseBased(val responseValues: Map<String, Any>) : SurveyQuestionBranching` |
| SpecificQuestion | `class SpecificQuestion(val index: Int) : SurveyQuestionBranching` |

### `SurveyQuestionBranchingType`

```kotlin
enum SurveyQuestionBranchingType : Enum<SurveyQuestionBranchingType>
```

#### Entries

| | |
|---|---|
| NEXT_QUESTION | `NEXT_QUESTION` |
| END | `END` |
| RESPONSE_BASED | `RESPONSE_BASED` |
| SPECIFIC_QUESTION | `SPECIFIC_QUESTION` |

#### Properties

| Name | Summary |
|---|---|
| value | `val value: String` |

### `SurveyQuestionTranslation`

```kotlin
data class SurveyQuestionTranslation(val question: String? = null, val description: String? = null, val buttonText: String? = null, val link: String? = null, val lowerBoundLabel: String? = null, val upperBoundLabel: String? = null, val choices: List<String>? = null)
```

Localized overrides for a survey question.

Fields are a superset across question types: `link` only applies to link questions, `lowerBoundLabel` / `upperBoundLabel` only to rating questions, and `choices` only to single/multiple choice questions. Irrelevant fields are ignored for other types.

#### Properties

| Name | Summary |
|---|---|
| buttonText | `val buttonText: String? = null` |
| choices | `val choices: List<String>? = null` |
| description | `val description: String? = null` |
| link | `val link: String? = null` |
| lowerBoundLabel | `val lowerBoundLabel: String? = null` |
| question | `val question: String? = null` |
| upperBoundLabel | `val upperBoundLabel: String? = null` |

### `SurveyQuestionType`

```kotlin
enum SurveyQuestionType : Enum<SurveyQuestionType>
```

#### Entries

| | |
|---|---|
| OPEN | `OPEN` |
| LINK | `LINK` |
| RATING | `RATING` |
| MULTIPLE_CHOICE | `MULTIPLE_CHOICE` |
| SINGLE_CHOICE | `SINGLE_CHOICE` |

#### Properties

| Name | Summary |
|---|---|
| value | `val value: String` |

### `SurveyRatingDisplayType`

```kotlin
enum SurveyRatingDisplayType : Enum<SurveyRatingDisplayType>
```

#### Entries

| | |
|---|---|
| NUMBER | `NUMBER` |
| EMOJI | `EMOJI` |

#### Properties

| Name | Summary |
|---|---|
| value | `val value: String` |

### `SurveySchedule`

```kotlin
enum SurveySchedule : Enum<SurveySchedule>
```

#### Entries

| | |
|---|---|
| ONCE | `ONCE` |
| RECURRING | `RECURRING` |
| ALWAYS | `ALWAYS` |

#### Properties

| Name | Summary |
|---|---|
| value | `val value: String` |

### `SurveyTextContentType`

```kotlin
enum SurveyTextContentType : Enum<SurveyTextContentType>
```

#### Entries

| | |
|---|---|
| HTML | `HTML` |
| TEXT | `TEXT` |

#### Properties

| Name | Summary |
|---|---|
| value | `val value: String` |

### `SurveyTranslation`

```kotlin
data class SurveyTranslation(val name: String? = null, val thankYouMessageHeader: String? = null, val thankYouMessageDescription: String? = null, val thankYouMessageCloseButtonText: String? = null, val introScreenHeader: String? = null, val introScreenDescription: String? = null, val introScreenButtonText: String? = null)
```

Localized overrides for a survey, keyed by language code (e.g. &quot;fr&quot;, &quot;pt-BR&quot;).

The survey-level `description` is intentionally NOT translatable — it is only used for internal previews and never rendered to end users.

#### Properties

| Name | Summary |
|---|---|
| introScreenButtonText | `val introScreenButtonText: String? = null` |
| introScreenDescription | `val introScreenDescription: String? = null` |
| introScreenHeader | `val introScreenHeader: String? = null` |
| thankYouMessageCloseButtonText | `val thankYouMessageCloseButtonText: String? = null` |
| thankYouMessageDescription | `val thankYouMessageDescription: String? = null` |
| thankYouMessageHeader | `val thankYouMessageHeader: String? = null` |

### `SurveyType`

```kotlin
enum SurveyType : Enum<SurveyType>
```

#### Entries

| | |
|---|---|
| POPOVER | `POPOVER` |
| API | `API` |
| WIDGET | `WIDGET` |

#### Properties

| Name | Summary |
|---|---|
| value | `val value: String` |

How to use the Android SDK v3
============

## Setup

```kotlin
// app/build.gradle.kts
implementation("com.posthog:posthog-android:$latestVersion")
```

```kotlin
import com.posthog.PostHog
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig

val config = PostHogAndroidConfig(apiKey)
PostHogAndroid.setup(applicationContext, config)
```

Set a custom `host` (Self-Hosted)

```kotlin
val config = PostHogAndroidConfig(apiKey, host)
```

Change the default configuration

```kotlin
val config = PostHogAndroidConfig(apiKey).apply { 
    captureScreenViews = false
    captureDeepLinks = false
    captureApplicationLifecycleEvents = false
    debug = true
    // .. and more
}
```

If you don't want to use the global/singleton instance, you can create your own PostHog SDK instance
and hold it

```kotlin
val config = PostHogAndroidConfig(apiKey)
val postHog = PostHogAndroid.with(applicationContext, config)

postHog.capture("user_signed_up")
```

Enable or Disable the SDK to capture events

```kotlin
// During SDK setup
val config = PostHogAndroidConfig(apiKey).apply {
    // the SDK is enabled by default
    optOut = true
}
PostHogAndroid.setup(applicationContext, config)

// At runtime
PostHog.optOut()

// Check it and opt-in
if (PostHog.isOptOut()) {
    PostHog.optIn()
}
```

Capture a screen view event

```kotlin
// Automatically
val config = PostHogAndroidConfig(apiKey).apply {
    // it's enabled by default
    captureScreenViews = true
}
PostHogAndroid.setup(applicationContext, config)

// Or manually
PostHog.screen("Dashboard", properties = mapOf("url" to "...", "background" to "blue"))
```

Capture an event

```kotlin
PostHog.capture("user_signed_up", properties = mapOf("is_free_trial" to true))
// check out the `userProperties`, `userPropertiesSetOnce` and `groupProperties` parameters.
```

Identify the user

```kotlin
PostHog.identify(
    "user123",
    userProperties = mapOf("email" to "user@posthog.com")
)
```

Create an alias for the current user

```kotlin
PostHog.alias("theAlias")
```

Identify a group

```kotlin
PostHog.group("company", "company_id_in_your_db", groupProperties = mapOf("name" to "Awesome Inc."))
```

Registering and unregistering a context to be sent for all the following events

```kotlin
// Register
PostHog.register("team_id", 22)

// Unregister
PostHog.unregister("team_id")
```

Load feature flags automatically

```kotlin
val config = PostHogAndroidConfig(apiKey).apply {
    preloadFeatureFlags = true
    // get notified when feature flags are loaded
    onFeatureFlags = PostHogOnFeatureFlags {
        if (PostHog.isFeatureEnabled("paidUser", defaultValue = false)) {
            // do something
        }
    }
}
PostHogAndroid.setup(applicationContext, config)

// And/Or manually
PostHog.reloadFeatureFlags {
    if (PostHog.isFeatureEnabled("paidUser", defaultValue = false)) {
        // do something
    }
}
```

Read feature flags

```kotlin
val paidUser = PostHog.isFeatureEnabled("paidUser", defaultValue = false)

// Or
val paidUser = PostHog.getFeatureFlag("paidUser", defaultValue = false) as Boolean
```

Read feature flags variant/payload

```kotlin
val premium = PostHog.getFeatureFlagPayload("premium", defaultValue = false) as Boolean
```

Read the current `distinctId`

```kotlin
val distinctId = PostHog.distinctId()
```

Sanitize event properties

```kotlin
val config = PostHogAndroidConfig(apiKey).apply {
    propertiesSanitizer = PostHogPropertiesSanitizer { properties ->
        properties.apply {
            // will remove the property from the event
            remove("\$device_name")
        }
    }
}
PostHogAndroid.setup(applicationContext, config)
```

Flush the SDK by sending all the pending events right away

```kotlin
PostHog.flush()
```

Reset the SDK and delete all the cached properties

```kotlin
PostHog.reset()
```

Close the SDK

```kotlin
PostHog.close()
```

## Android Session Recording

Enable `Record user sessions` on the [PostHog project settings](https://us.posthog.com/settings/project-replay#replay).

[Authorized Domains for Replay](https://us.posthog.com/settings/project-replay#replay-authorized-domains) has to be disabled, for now.

Enable the SDK to capture Session Recording.

```kotlin
val config = PostHogAndroidConfig(apiKey).apply {
    // sessionReplay is disabled by default
    sessionReplay = true
    // sessionReplayConfig is optional, they are enabled by default
    sessionReplayConfig.maskAllTextInputs = true
    sessionReplayConfig.maskAllImages = true
    sessionReplayConfig.captureLogcat = true
}
```

If you don't want to mask everything, you can disable the mask config above and mask specific views using the `ph-no-capture` tag.

```xml
<ImageView
    android:id="@+id/imvAndroid"
    android:layout_width="230dp"
    android:layout_height="391dp"
    android:src="@drawable/android_logo"
    android:tag="ph-no-capture"
/>
```

Add the `PostHogOkHttpInterceptor` to your `OkHttpClient` to capture network requests.

```kotlin
private val client = OkHttpClient.Builder()
    .addInterceptor(PostHogOkHttpInterceptor(captureNetworkTelemetry = true))
    .build()
```

### Limitations

- Requires Android API >= 26, otherwise it's a NoOp.
- Not compatible and/or tested with [Jetpack Compose](https://developer.android.com/jetpack/compose) yet.
- It's a representation of the user's screen, not a video recording nor a screenshot.
  - Custom views are not fully supported.
- WebView is not supported, a placeholder will be shown.

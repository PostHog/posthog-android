# Getting started

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

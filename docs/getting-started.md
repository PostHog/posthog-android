# Getting started

The Android SDK runs in Android apps.

To send your first event, add the dependency, then call `PostHogAndroid.setup` once in `Application.onCreate` with your project token and host from your [project settings](https://app.posthog.com/project/settings).

After `setup`, PostHog captures app lifecycle events, such as `Application Opened`, by itself. Use `capture` for your own events.

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

## Tracing headers

Set `tracingHeaders` and add `PostHogOkHttpInterceptor` to link Android network requests to the backend events, errors, and LLM traces that a server-side PostHog SDK captures. Matching requests carry `X-POSTHOG-DISTINCT-ID` and `X-POSTHOG-SESSION-ID` when those values exist. List hostnames only, with no protocol, path, port, or wildcard.

## Offline behavior

The SDK keeps capturing events while the device is offline and stores them on the device. It sends them when the app restarts with a connection. `flush()` returns early while the device is offline and sends nothing. Online, `flush()` starts sending in the background and doesn't wait for the request to finish, so it doesn't guarantee delivery.

Super properties you set with `register` also persist across sessions.

## Permissions

The SDK declares `android.permission.INTERNET` and `android.permission.ACCESS_NETWORK_STATE`, and Android's manifest merger adds them to your app. It doesn't declare or need an Android `Service`.

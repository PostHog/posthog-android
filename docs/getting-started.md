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

## Connect requests to backend events

Set `tracingHeaders` and install `PostHogOkHttpInterceptor` on each `OkHttpClient` whose requests should carry PostHog context. This links requests to backend events, errors and LLM traces captured by a server-side PostHog SDK.

```kotlin
import com.posthog.PostHogOkHttpInterceptor
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig
import okhttp3.OkHttpClient

val config = PostHogAndroidConfig("<ph_project_token>", host = "<ph_client_api_host>").apply {
    tracingHeaders = listOf("api.example.com")
}
PostHogAndroid.setup(this, config)

val httpClient = OkHttpClient.Builder()
    .addInterceptor(PostHogOkHttpInterceptor())
    .build()
```

Use exact hostnames, with no protocol, path, port or wildcard subdomain. Matching requests include `X-POSTHOG-DISTINCT-ID` and `X-POSTHOG-SESSION-ID` only when their values are available.

## Permissions

The SDK declares `android.permission.INTERNET` and `android.permission.ACCESS_NETWORK_STATE`, and Android's manifest merger adds them to your app. It doesn't declare or need an Android `Service`.

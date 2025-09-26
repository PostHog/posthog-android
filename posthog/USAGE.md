# How to use the PostHog Core SDK

The PostHog core package provides the core PostHog functionality as a pure Kotlin library. This is intended to be consumed by other environment specific SDKs.

## Setup

```kotlin
// build.gradle.kts
implementation("com.posthog:posthog:$latestVersion")
```

```kotlin
import com.posthog.PostHog
import com.posthog.PostHogConfig

val config = PostHogConfig(apiKey)
val postHog = PostHog(config)
```

Set a custom `host` (Self-Hosted)

```kotlin
val config = PostHogConfig(apiKey, host)
```

Change the default configuration

```kotlin
val config = PostHogConfig(apiKey).apply {
    debug = true
    // ... other core configuration options
}
```

## Basic Usage

Capture an event

```kotlin
postHog.capture("user_signed_up", properties = mapOf("is_free_trial" to true))
```

Identify the user

```kotlin
postHog.identify(
    "user123",
    userProperties = mapOf("email" to "user@posthog.com")
)
```

Create an alias for the current user

```kotlin
postHog.alias("theAlias")
```

Read the current `distinctId`

```kotlin
val distinctId = postHog.distinctId()
```

Flush the SDK by sending all the pending events right away

```kotlin
postHog.flush()
```

Reset the SDK and delete all the cached properties

```kotlin
postHog.reset()
```

Close the SDK

```kotlin
postHog.close()
```

## Differences from posthog-android

The core package (`com.posthog:posthog`) is a pure JVM library that:

- Should be consumed by more specific JVM SDKs
- Does not include Android-specific features like automatic screen view tracking
- Does not include session recording capabilities
- Requires manual event capture and lifecycle management
- Provides building blocks for server-side applications, desktop applications, or custom Android integrations

For full Android app integration with automatic features, use `com.posthog:posthog-android` instead.

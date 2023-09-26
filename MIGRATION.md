Migration from v2 to v3
============

## Setup v2

```kotlin
import com.posthog.PostHog

val apiKey = "..."
val posthog = PostHog.Builder(applicationContext, apiKey)
    .captureApplicationLifecycleEvents()
    captureDeepLinks()
    .recordScreenViews()
    .build()
PostHog.setSingletonInstance(posthog)

PostHog.with(applicationContext).capture("event")
```

## Setup v3

```kotlin
import com.posthog.PostHog
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig

val apiKey = "..."
val config = PostHogAndroidConfig(apiKey).apply {
    captureApplicationLifecycleEvents = true
    captureDeepLinks = true
    captureScreenViews = true
}
PostHogAndroid.setup(applicationContext, config)

PostHog.capture("event")
```

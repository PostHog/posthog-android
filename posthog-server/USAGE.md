# How to use the PostHog Server SDK

The PostHog Server SDK is designed for server-side applications and provides a simple, stateless interface for sending events and managing feature flags.

Java examples given in this document aim to work on any Java versions 8 and up.

## Setup

### Gradle

```kotlin
// build.gradle.kts
implementation("com.posthog:posthog-server:$latestVersion")
```

```groovy
// build.gradle
implementation 'com.posthog:posthog-server:$latestVersion'
```

### Kotlin

```kotlin
import com.posthog.server.PostHog
import com.posthog.server.PostHogConfig

val config = PostHogConfig(apiKey = "phc_your_api_key_here")
val postHog = PostHog.with(config)
```

### Java

```java
import com.posthog.server.PostHog;
import com.posthog.server.PostHogConfig;
import com.posthog.server.PostHogInterface;

PostHogConfig config = new PostHogConfig("phc_your_api_key_here");
PostHogInterface postHog = PostHog.with(config);
```

## Configuration

### Set a custom host (Self-Hosted)

#### Kotlin

```kotlin
val config = PostHogConfig(
    apiKey = "phc_your_api_key_here",
    host = "https://your-posthog-instance.com"
)
```

#### Java

```java
PostHogConfig config = new PostHogConfig(
    "phc_your_api_key_here",
    "https://your-posthog-instance.com"
);
```

#### Builder Pattern Configuration (Java)

```java
PostHogConfig config = PostHogConfig.builder("phc_your_api_key_here")
    .host("https://your-posthog-instance.com")
    .debug(true)
    .sendFeatureFlagEvent(false)
    .preloadFeatureFlags(true)
    .flushAt(50)
    .maxQueueSize(2000)
    .maxBatchSize(100)
    .flushIntervalSeconds(60)
    .featureFlagCacheSize(1000)
    .featureFlagCacheMaxAgeMs(5 * 60 * 1000)
    .build();
```

### Available Configuration Options

- `apiKey`: Your PostHog API key (required)
- `host`: PostHog host URL (default: `https://us.i.posthog.com`)
- `debug`: Enable debug logging (default: `false`)
- `sendFeatureFlagEvent`: Send feature flag events automatically (default: `true`)
- `preloadFeatureFlags`: Preload feature flags on startup (default: `true`)
- `remoteConfig`: Enable remote configuration (default: `true`)
- `flushAt`: Number of events to batch before sending (default: `20`)
- `maxQueueSize`: Maximum events in memory/disk (default: `1000`)
- `maxBatchSize`: Maximum events per batch (default: `50`)
- `flushIntervalSeconds`: Interval between automatic flushes (default: `30`)
- `featureFlagCacheSize`: The maximum number of feature flags results to cache (default: `1000`)
- `featureFlagCacheMaxAgeMs`: The maximum age of a feature flag cache record in memory in milliseconds (default: `300000` or five minutes)
- `localEvaluation`: Enable local evaluation of feature flags (default: `false`)
- `personalApiKey`: Personal API key required for local evaluation (default: `null`)
- `pollIntervalSeconds`: Interval for polling flag definitions for local evaluation (default: `30`)

## Capturing Events

### Simple Event Capture

#### Kotlin & Java

```kotlin
postHog.capture("user123", "button_clicked")
```

### Event with Properties

#### Kotlin

```kotlin
postHog.capture(
    distinctId = "user123",
    event = "purchase_completed",
    properties = mapOf("product" to "premium_plan", "price" to 99.99),
    userProperties = mapOf("plan" to "premium"),
    userPropertiesSetOnce = mapOf("first_purchase_date" to "2023-01-15"),
    groups = mapOf("company" to "acme_corp")
)
```

#### Java

> [!WARNING]
> Avoid using anonymous inner classes (double braces) to initialize your HashMaps. These hold references to the outer class and will not serialize properly.

```java
Map<String, Object> properties = new HashMap<>();
properties.put("button_name", "signup");
properties.put("page", "landing");
properties.put("experiment_variant", "blue_button");
postHog.capture("user123", "button_clicked", properties);
```

#### Builder Pattern (Java)

```java
postHog.capture(
    "distinct-id",
    "new-purchase",
    PostHogCaptureOptions
        .builder()
        .property("item", "SKU-0000")
        .property("sale", false)
        .userProperty("plan", "premium")
        .userPropertySetOnce("signup_date", "2023-01-01")
        .build()
);
```

#### Overriding event timestamps

If you need to change the timestamp on an event, you can do so by using the `timestamp` builder method on `PostHogCaptureOptions`.

```java
postHog.capture(
    "distinct-id",
    "past-event",
    PostHogCaptureOptions
        .builder()
        .timestamp(customTime)
        .build()
);
```

Or, directly via named property in Kotlin:

```kotlin
postHog.capture(
    distinctId = "distinct-id",
    event = "past-event",
    timestamp = customTime
)
```

## Error Tracking

PostHog provides error tracking to help you monitor and debug errors in your application. Use the `captureException` API to log exceptions to PostHog.

### Capture Exception with Distinct ID

When you provide a `distinctId`, the exception is associated with a specific person profile in PostHog:

#### Kotlin

```kotlin
try {
    // Your code that might throw an exception
    throw RuntimeException("Something went wrong")
} catch (e: Exception) {
    val exceptionProperties = mapOf(
        "service" to "payment-processor",
        "context" to "checkout_flow"
    )
    postHog.captureException(e, "user123", exceptionProperties)
}
```

#### Java

```java
try {
    // Your code that might throw an exception
    throw new RuntimeException("Something went wrong");
} catch (Exception e) {
    Map<String, Object> exceptionProperties = new HashMap<>();
    exceptionProperties.put("service", "payment-processor");
    exceptionProperties.put("context", "checkout_flow");
    postHog.captureException(e, "user123", exceptionProperties);
}
```

### Capture Exception without Distinct ID (Server-side Errors)

When no `distinctId` is provided, the exception is treated as originating from a non-person entity. This is ideal for server-side errors, background processes, or system-level exceptions:

#### Kotlin

```kotlin
try {
    // Server-side operation
    processBackgroundJob()
} catch (e: Exception) {
    val exceptionProperties = mapOf(
        "job_type" to "email_batch",
    )
    postHog.captureException(e, exceptionProperties)
}
```

#### Java

```java
try {
    // Server-side operation
    processBackgroundJob();
} catch (Exception e) {
    Map<String, Object> exceptionProperties = new HashMap<>();
    exceptionProperties.put("job_type", "email_batch");
    postHog.captureException(e, exceptionProperties);
}
```

### Simple Exception Capture

You can also capture exceptions without additional properties:

#### Kotlin & Java

```kotlin
try {
    riskyOperation()
} catch (e: Exception) {
    postHog.captureException(e)
}
```

## User Identification

#### Kotlin

```kotlin
postHog.identify(
    "user123",
    userProperties = mapOf("current_plan" to "premium"),
    userPropertiesSetOnce = mapOf(
        "signup_date" to "2023-01-01",
        "initial_referrer" to "google"
    )
)
```

#### Java

```java
Map<String, Object> userProperties = new HashMap<>();
userProperties.put("current_plan", "premium");

Map<String, Object> userPropertiesSetOnce = new HashMap<>();
userPropertiesSetOnce.put("signup_date", "2023-01-01");
userPropertiesSetOnce.put("initial_referrer", "google");

postHog.identify("user123", userProperties, userPropertiesSetOnce);
```

## Feature Flags

### Local Evaluation (Experimental)

Local evaluation allows the SDK to evaluate feature flags locally without making API calls for each flag check. This reduces latency and API costs.

**How it works:**

1. The SDK periodically polls for flag definitions from PostHog (every 30 seconds by default)
2. Flags are evaluated locally using cached definitions and properties provided by the caller
3. If evaluation is inconclusive (missing properties, etc.), the SDK falls back to the API

**Requirements:**

- A feature flags secure API key _or_ a personal API key
  - A feature flags secure API key can be obtained via PostHog → Settings → Project → Feature Flags → Feature Flags Secure API key
  - A personal API key can be generated via PostHog → Settings → Account → Personal API Keys
- The `localEvaluation` config option set to `true`

#### Kotlin

```kotlin
val config = PostHogConfig(
    apiKey = "phc_your_api_key_here",
    host = "https://your-posthog-instance.com",
    localEvaluation = true,
    personalApiKey = "phx_your_personal_api_key_here",
    pollIntervalSeconds = 30  // Optional: customize polling interval
)
```

#### Java

```java
PostHogConfig config = PostHogConfig.builder("phc_your_api_key_here")
    .host("https://your-posthog-instance.com")
    .localEvaluation(true)
    .personalApiKey("phx_your_personal_api_key_here")
    .pollIntervalSeconds(30)  // Optional: customize polling interval
    .build();
```

**Benefits:**

- **Reduced latency**: No API call needed for most flag evaluations
- **Lower costs**: Fewer API requests in most cases
- **Offline support**: Flags continue to work with cached definitions

**Limitations:**

- Requires person/group properties to be provided with each call
- Falls back to API for cohort-based flags without local cohort data
- May not reflect real-time flag changes (respects polling interval)

### Check if Feature is Enabled

#### Kotlin

```kotlin
val isEnabled = postHog.isFeatureEnabled("user123", "beta_feature", false)
if (isEnabled) {
    // Show beta feature
}
```

#### Java

```java
boolean isEnabled = postHog.isFeatureEnabled("user123", "beta_feature", false);
if (isEnabled) {
    // Show beta feature
}
```

### Get Feature Flag Value

#### Kotlin

```kotlin
val flagValue = postHog.getFeatureFlag("user123", "theme_variant", "light")
when (flagValue) {
    "dark" -> applyDarkTheme()
    "light" -> applyLightTheme()
    else -> applyDefaultTheme()
}
```

#### Java

```java
Object flagValue = postHog.getFeatureFlag("user123", "theme_variant", "light");
String theme = flagValue instanceof String ? (String) flagValue : "light";
switch (theme) {
    case "dark":
        applyDarkTheme();
        break;
    case "light":
        applyLightTheme();
        break;
    default:
        applyDefaultTheme();
}
```

### Get Feature Flag Payload

#### Kotlin

```kotlin
val payload = postHog.getFeatureFlagPayload("user123", "experiment_config")
if (payload is Map<*, *>) {
    val config = payload as Map<String, Any>
    val buttonColor = config["button_color"] as? String ?: "blue"
    val showBanner = config["show_banner"] as? Boolean ?: false
}
```

#### Java

```java
Object payload = postHog.getFeatureFlagPayload("user123", "experiment_config");
if (payload instanceof Map) {
    Map<String, Object> config = (Map<String, Object>) payload;
    String buttonColor = config.get("button_color") instanceof String
        ? (String) config.get("button_color") : "blue";
    boolean showBanner = config.get("show_banner") instanceof Boolean
        ? (Boolean) config.get("show_banner") : false;
}
```

## Groups

### Create a Group

#### Kotlin

```kotlin
val groupProperties = mapOf(
    "name" to "Acme Corporation",
    "industry" to "Technology",
    "employee_count" to 500
)
postHog.group("user123", "company", "acme_corp", groupProperties)
```

#### Java

```java
Map<String, Object> groupProperties = new HashMap<>();
groupProperties.put("name", "Acme Corporation");
groupProperties.put("industry", "Technology");
groupProperties.put("employee_count", 500);
postHog.group("user123", "company", "acme_corp", groupProperties);
```

### Simple Group Creation

#### Kotlin & Java

```kotlin
postHog.group("user123", "organization", "org_123")
```

## User Aliases

### Create an Alias

#### Kotlin & Java

```kotlin
postHog.alias("user123", "john_doe_alias")
```

## SDK Management

### Flush Events

Force send all queued events immediately:

#### Kotlin & Java

```kotlin
postHog.flush()
```

### Enable Debug Mode

#### Kotlin & Java

```kotlin
postHog.debug(true)  // Enable debug logging
postHog.debug(false) // Disable debug logging
```

### Close the SDK

#### Kotlin & Java

```kotlin
postHog.close()
```

## Error Handling

The PostHog Server SDK is designed to be resilient and won't throw exceptions for normal operations. Network errors and API failures are handled gracefully with automatic retries and fallback behaviors.

## Thread Safety

The PostHog Server SDK is thread-safe and can be used safely from multiple threads concurrently.

## Requirements

- Java 8 or higher
- Kotlin 1.6 or higher (if using Kotlin)
- Internet connection for sending events to PostHog

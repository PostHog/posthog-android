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

## Install the SDK

Install the SDK with a build system like [Gradle](https://gradle.org/), so you can upgrade to new versions with one line. Add the `posthog-android` module to your app's `build.gradle` or `build.gradle.kts`.

In `app/build.gradle`:

```gradle
dependencies {
  implementation 'com.posthog:posthog-android:4.+'
}
```

In `app/build.gradle.kts`:

```kotlin
dependencies {
  implementation("com.posthog:posthog-android:4.+")
}
```

Initialize the client in your `Application` subclass:

```kotlin
import android.app.Application
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig

class SampleApp : Application() {

    companion object {
        const val POSTHOG_API_KEY = "<ph_project_token>"
        // usually 'https://us.i.posthog.com' or 'https://eu.i.posthog.com'
        const val POSTHOG_HOST = "<ph_client_api_host>"
    }

    override fun onCreate() {
        super.onCreate()

        val config = PostHogAndroidConfig(
            projectToken = POSTHOG_API_KEY,
            host = POSTHOG_HOST // TIP: host is optional if you use https://us.i.posthog.com
        )
        PostHogAndroid.setup(this, config)
    }
}
```

You can also let the wizard do this. Run it in your project directory. It detects your framework, installs the SDK, and sets up your first events:

```bash
npx -y @posthog/wizard@latest
```

## Capture custom events

Send a custom event with `capture`:

```kotlin
import com.posthog.PostHog

PostHog.capture(event = "user_signed_up")
```

> **Name events as `[object] [verb]`.** The object is the entity the behavior relates to, and the verb is the behavior itself. For example, `project created`, `user signed up`, or `invite sent`.

To send more information with the event, pass a [properties](https://posthog.com/docs/data/events#event-properties) map:

```kotlin
import com.posthog.PostHog

PostHog.capture(
    event = "user_signed_up",
    properties = mapOf(
        "login_type" to "email",
        "is_free_trial" to true
    )
)
```

## The autocaptured events

PostHog captures these events for you:

- **Application Opened.** The app opens from a closed state, or comes to the foreground, for example from the app switcher.
- **Deep Link Opened.** The app opens from a deep link.
- **Application Backgrounded.** The user sends the app to the background.
- **Application Installed.** The app is installed.
- **Application Updated.** The app is updated.
- **$screen.** The user navigates, if you use `android.app.Activity`.
- **$exception.** An uncaught exception is thrown. To get these, turn on [Android error tracking](https://posthog.com/docs/error-tracking/installation/android) and exception autocapture in the SDK config.

## Capture screen views

With `captureScreenViews = true`, the default, PostHog records screen changes by itself (see [The configuration options](#the-configuration-options)).

The `screenTitle` is the [`<activity>`](https://developer.android.com/guide/topics/manifest/activity-element)'s `android:label`. If that's not set, it falls back to the [`<application>`](https://developer.android.com/guide/topics/manifest/application-element)'s `android:label`, or the `<activity>`'s `android:name`.

```xml
<activity
    android:name="com.example.app.ChildActivity"
    android:label="@string/title_child_activity"
    ...
</activity>
```

To send a screen event yourself, call `screen`. It needs a `screenTitle`, and takes an optional `properties` map:

```kotlin
import com.posthog.PostHog

PostHog.screen(
    screenTitle = "Dashboard",
    properties = mapOf(
        "background" to "blue",
        "hero" to "superhog"
    )
)
```

## Identify users

`identify` ties events to a specific user, so you can follow them across sessions, devices, and platforms. Read [Identifying users](https://posthog.com/docs/integrate/identifying-users) to use it correctly.

An `identify` call takes these arguments:

- **`distinctId`.** Required. A unique identifier for your user, typically their email or database ID.
- **`userProperties`.** Optional. A map of [person properties](https://posthog.com/docs/product-analytics/person-properties) to set.
- **`userPropertiesSetOnce`.** Optional. Like `userProperties`, but it only sets a property the user doesn't have yet. See [the difference between the two](https://posthog.com/docs/product-analytics/person-properties#what-is-the-difference-between-set-and-set_once).

```kotlin
import com.posthog.PostHog

PostHog.identify(
    distinctId = distinctID,
    userProperties = mapOf(
        "name" to "Max Hedgehog", 
        "email" to "max@hedgehogmail.com"
    ),
    userPropertiesSetOnce = mapOf(
        "date_of_first_log_in" to "2024-03-01"
    ),
)
```

Call `identify` as soon as you can, typically right after the user logs in. Events from the user's sessions are then tied to them, and their earlier anonymous events are linked to the user.

To get the current user's distinct ID, call `distinctId()`. It returns the ID PostHog generated, or the ID you passed to `identify()`. You can use it to check whether you already identified the user.

To reset the user's ID and anonymous ID, call `reset`. You'd usually do this right after the user logs out:

```kotlin
import com.posthog.PostHog

PostHog.reset()
```

## Alias a user

Use `alias` to assign another distinct ID to the same user. This helps when your primary distinct ID isn't available, for example when an ID you use on the frontend isn't available in your backend.

```kotlin
/**
 * Create an alias for the current user.
 */
PostHog.alias("distinct_id")
```

Read the docs on [alias](https://posthog.com/docs/product-analytics/identify#alias-assigning-multiple-distinct-ids-to-the-same-user) to use it correctly.

## Choose person profiles

PostHog captures two types of events: [anonymous and identified](https://posthog.com/docs/data/anonymous-vs-identified-events). For an identified event, PostHog creates a [person profile](https://posthog.com/docs/data/persons) for the user. For an anonymous event, it doesn't.

- **Identified events.** They let you attribute events to specific users and attach [person properties](https://posthog.com/docs/product-analytics/person-properties). Use them for logged-in users in B2B and B2C SaaS apps, for user segmented product analysis, and for growth and marketing teams who analyze the complete conversion lifecycle.
- **Anonymous events.** They carry no individually identifiable data. Use them for marketing websites, content-focused sites, and B2C apps where users don't sign up or log in.

> **Anonymous events cost less.** They can be up to 4x cheaper than identified events, so only capture identified events when you need them.

The `personProfiles` config option decides which type the SDK captures:

| Value | Captures |
| --- | --- |
| `PersonProfiles.IDENTIFIED_ONLY` | Anonymous events, until the user has a person profile |
| `PersonProfiles.ALWAYS` | Identified events for all events |
| `PersonProfiles.NEVER` | Anonymous events for all events |

`IDENTIFIED_ONLY` is the default and the recommended value:

```kotlin
val config = PostHogAndroidConfig(
   projectToken = POSTHOG_API_KEY,
   host = POSTHOG_HOST,
).apply {
   personProfiles = PersonProfiles.IDENTIFIED_ONLY
}
```

With `IDENTIFIED_ONLY`, calling `identify()`, `alias()`, or `group()` creates a person profile for the user. Every later event for that user is captured as an identified event.

## Set person properties

To set [person properties](https://posthog.com/docs/product-analytics/person-properties) through an event, pass `userProperties` to `capture`. The properties are set on the user who triggered the event:

```kotlin
import com.posthog.PostHog

PostHog.capture(
    event = "button_b_clicked",
    properties = mapOf("color" to "blue"),
    userProperties = mapOf(
        "string" to "value1",
        "integer" to 2
    )
)
```

`userPropertiesSetOnce` works like `userProperties`, but it only sets a property the user doesn't already have:

```kotlin
import com.posthog.PostHog

PostHog.capture(
    event = "button_b_clicked",
    properties = mapOf("color" to "blue"),
    userPropertiesSetOnce = mapOf(
        "string" to "value1",
        "integer" to 2
    )
)
```

## Register super properties

Super properties are event properties you set once. The SDK then sends them with every `capture` call, `$screen` events included. They persist across sessions.

Set one with `PostHog.register`, which takes a key and a value:

```kotlin
import com.posthog.PostHog

PostHog.register("team_id", 22)
```

Every event the user sends after this call includes `"team_id": 22`, so you can filter events on `team_id = 22`.

Super properties are stored on events, not on the user. To store properties on the user, use `PostHog.identify` (see [Identify users](#identify-users)).

To stop sending a super property, call `PostHog.unregister`. Later events don't include it:

```kotlin
import com.posthog.PostHog

PostHog.unregister("team_id")
```

When a user logs out, call `PostHog.reset` instead. It clears all stored super properties and more.

## Opt out users

You can opt users out of data capture in two ways.

To opt users out by default, set `optOut` to `true` in your config:

```kotlin
val config = PostHogAndroidConfig(
    projectToken = "<ph_project_token>",
    host = "<ph_client_api_host>"
)
config.optOut = true
PostHogAndroid.setup(this, config)
```

To opt out one user, call `optOut()`:

```kotlin
PostHog.optOut()
```

To opt a user back in:

```kotlin
PostHog.optIn()
```

To check if a user is opted out:

```kotlin
PostHog.isOptOut()
```

## Flush the queue

Calls to the SDK don't block. The SDK puts events in an internal queue, batches the requests, and flushes them in the background.

`flushAt` sets how many events queue before a flush. The default is `20`. A value of `1` sends events immediately and uses more battery.

`flushIntervalSeconds` sets the longest wait before a flush. The default is `30`. After that, queued events are sent no matter how many there are:

```kotlin
import com.posthog.android.PostHogAndroidConfig

val config = PostHogAndroidConfig(projectToken = POSTHOG_API_KEY, host = POSTHOG_HOST).apply {
    flushAt = 20
    flushIntervalSeconds = 30
}
```

To send queued events now, rather than wait for the next batch, flush the queue yourself:

```kotlin
import com.posthog.PostHog

PostHog.flush() 
```

> **A flush isn't a delivery guarantee.** It starts sending queued events in the background and doesn't wait for the request to finish.

## The offline queue

The SDK keeps capturing events while the device is offline. It stores them in a queue in the device's file storage, and flushes them when the device is back online.

- **The size limit.** The queue holds up to `maxQueueSize` events.
- **A full queue.** The oldest event is deleted first.
- **App restarts.** The queue is flushed when the app restarts and the device is online.
- **Offline flushes.** A `flush()` call while the device is offline stops early, and the events stay queued.

## Capture AI events

To send [AI observability](https://posthog.com/docs/ai-observability) events you build yourself, such as `$ai_generation`, `$ai_span`, or `$ai_embedding`, call `captureAi` instead of `capture`. It sends the event to PostHog's [AI endpoint](https://posthog.com/docs/ai-observability/large-events), which accepts much larger events than `capture`.

```kotlin
import com.posthog.PostHog

val uuid = PostHog.captureAi(
    event = "\$ai_generation",
    properties = mapOf(
        "\$ai_trace_id" to traceId,
        "\$ai_model" to "gpt-5",
        "\$ai_provider" to "openai",
        "\$ai_input_tokens" to 120,
        "\$ai_output_tokens" to 45,
    ),
)
```

The `PostHog.captureAi` reference entry covers the return value, the separate on-disk queue, and what gets dropped. `flush()` and `close()` send both queues.

The event size limit is 8 MiB plus 64 KiB once serialized. That's PostHog's 8 MiB limit on an AI event's properties, plus room for the rest of the event.

These `PostHogAndroidConfig` settings apply only to `captureAi`:

| Setting | Default | Sets |
| --- | --- | --- |
| `captureAiCompression` | `PostHogCompression.GZIP` | Request body compression |
| `captureAiMaxQueueSize` | `100` | AI events kept on disk |
| `captureAiTimeout` | `60` | Seconds without network activity |
| `captureAiMaxEventBytes` | `8454144` | Size of one serialized AI event |

- **`captureAiCompression`.** It's separate from `compression`.
- **`captureAiMaxQueueSize`.** Past this count, the oldest events are dropped. The 50 MiB limit applies too.
- **`captureAiTimeout`.** Other requests keep OkHttp's 10-second defaults.
- **`captureAiMaxEventBytes`.** You can only lower it. A larger value uses the default.

A value of `0` or less for a numeric setting uses its default.

## Use feature flags

[Feature flags](https://posthog.com/docs/feature-flags) let you deploy and roll back features safely, and target specific users and groups with them.

Check a boolean flag:

```kotlin
import com.posthog.PostHog

val result = PostHog.getFeatureFlagResult("flag-key")
if (result?.enabled == true) {
    // Do something differently for this user

    // Optional: fetch the payload from the same evaluation result
    val matchedFlagPayload = result.payload
}
```

Check a multivariate flag:

```kotlin
import com.posthog.PostHog

val result = PostHog.getFeatureFlagResult("flag-key")
if (result?.variant == "variant-key") { // replace "variant-key" with the key of your variant
    // Do something differently for this user

    // Optional: fetch the payload from the same evaluation result
    val matchedFlagPayload = result.payload
}
```

To inspect every loaded flag, call `PostHog.getAllFeatureFlags()`. It returns each flag's `key`, `enabled` state, `variant`, and `payload`. It doesn't send a `$feature_flag_called` event, so it won't affect your experiment results or flag usage analytics:

```kotlin
import com.posthog.PostHog

PostHog.getAllFeatureFlags()?.forEach { flag ->
    println("${flag.key} ${flag.enabled} ${flag.variant} ${flag.payload}")
}
```

Flag values are cached. If something changed for your user and you want fresh values, reload them:

```kotlin
import com.posthog.PostHog

PostHog.reloadFeatureFlags()
```

To track when someone sees or interacts with a feature, use `captureFeatureView` and `captureFeatureInteraction`:

```kotlin
import com.posthog.PostHog

PostHog.captureFeatureView("flag-key", flagVariant = "variant-key")
PostHog.captureFeatureInteraction("flag-key", flagVariant = "variant-key")
```

## Wait for flags

Every time a user opens the app, the SDK fetches that user's feature flags in the background and stores them. So on most screens the flags are available immediately, except the first time a user opens the app.

To wait for the flag request to finish, register an `onFeatureFlags` listener, or pass a callback to `reloadFeatureFlags`:

```kotlin
import com.posthog.PostHog

// Before PostHogAndroid.setup()
PostHog.onFeatureFlags {
    if (PostHog.isFeatureEnabled("flag-key")) {
        // do something
    }
}

// And/or after the SDK is initialized
PostHog.reloadFeatureFlags {
    if (PostHog.isFeatureEnabled("flag-key")) {
        // do something
    }
}
```

## Bootstrap feature flags

There's a delay between initializing PostHog and fetching feature flags, so flags aren't always available immediately. That makes them unusable for something like sending a user to a different screen based on a flag.

To have flags available immediately, initialize PostHog with precomputed values. This is called bootstrapping. After the SDK fetches flags from PostHog, it uses those values instead of the bootstrapped ones.

Set `config.bootstrap` before you call `setup()`. It seeds identity and flag values before the first `/flags` response:

```kotlin
import com.posthog.PostHogBootstrapConfig

val config = PostHogAndroidConfig(projectToken = POSTHOG_API_KEY, host = POSTHOG_HOST)
config.bootstrap = PostHogBootstrapConfig(
    distinctId = "distinct_id_of_your_user",
    isIdentifiedId = true,
    featureFlags = mapOf(
        "flag-1" to true,
        "variant-flag" to "control"
    )
)
PostHogAndroid.setup(this, config)
```

Bootstrapped identity applies during setup. On a fresh install, events captured during initialization, like `Application Installed`, carry your distinct ID instead of the SDK-generated UUID.

- **An anonymous bootstrap.** With `isIdentifiedId = false`, the default, it seeds the anonymous ID only when none is persisted yet. Once an anonymous ID exists on disk, or the person is identified, the SDK ignores it.
- **An identified bootstrap on a fresh install.** With `isIdentifiedId = true`, it seeds the distinct ID, marks the person identified, and generates a separate device ID. Use it for a signed-in identity your app already has, for example from a backend session token.
- **An identified bootstrap on a returning install.** A matching anonymous ID is marked identified without an `$identify` event. A different anonymous ID is merged through `identify()` when person profiles are on, which sends `$identify` unless capturing is opted out. A different, already-identified person is left untouched.
- **Person profiles set to `NEVER`.** The SDK keeps a different anonymous identity instead of merging it into an identified bootstrap.

Bootstrapped flags are served until the first `/flags` response, then replaced. A complete `/flags` response takes over entirely, so bootstrapped-only keys don't persist past it.

- **Enabled flags only.** A `true` boolean or a non-empty variant string is seeded. A `false` or empty value is dropped, matching posthog-js.
- **Payloads.** Seed them with the separate `featureFlagPayloads` option.
- **Serializable values.** Flag values and payloads must be JSON-serializable, or they're dropped.
- **Reset.** `reset()` clears bootstrapped flags.

The `onFeatureFlags` listener runs as soon as bootstrapped flags are applied, so startup logic can read them immediately. The SDK doesn't support the `sessionID` bootstrap option.

See the [SDK bootstrapping guide](https://posthog.com/docs/libraries/bootstrapping) for the cross-SDK overview.

## Run experiments

[Experiments](https://posthog.com/docs/experiments/start-here) use feature flags, so the code for an experiment looks like the feature flag code:

```kotlin
import com.posthog.PostHog

if (PostHog.getFeatureFlag("experiment-feature-flag-key") == "variant-name") {
    // do something
}
```

You can also [run experiments without feature flags](https://posthog.com/docs/experiments/running-experiments-without-feature-flags).

## Group your users

Group analytics ties the events of a person's session to a group, such as a team or an organization. Read the [group analytics](https://posthog.com/docs/product-analytics/group-analytics) guide for more.

> **Group analytics is a paid feature.** It's not available on the free plan. See the [pricing page](https://posthog.com/pricing).

Tie the events of this session to a group:

```kotlin
import com.posthog.PostHog

// organization is the group type, company_id_in_your_db is the group ID
PostHog.group(
    type = "company", 
    key = "company_id_in_your_db"
)
```

Tie the events of this session to a group, and update that group's properties:

```kotlin
import com.posthog.PostHog

PostHog.group(
    type = "company", 
    key = "company_id_in_your_db",
    groupProperties = mapOf("name" to "Awesome Inc.")
)
```

`name` is a special property. PostHog shows it as the name of the group. Without a `name` property, PostHog shows the group ID instead.

## Add tracing headers

Use `tracingHeaders` to connect Android network requests to backend events, errors, and LLM traces that a server-side PostHog SDK captures. The `PostHogOkHttpInterceptor` adds the headers, so install it on each `OkHttpClient` whose requests need PostHog context.

```kotlin
import com.posthog.PostHogOkHttpInterceptor
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig
import okhttp3.OkHttpClient

val config = PostHogAndroidConfig(
    projectToken = POSTHOG_API_KEY,
    host = POSTHOG_HOST,
).apply {
    tracingHeaders = listOf("api.example.com")
}

PostHogAndroid.setup(this, config)

val okHttpClient = OkHttpClient.Builder()
    .addInterceptor(PostHogOkHttpInterceptor())
    .build()
```

Hostnames are matched exactly. Don't include protocols, paths, ports, or wildcard subdomains. Matching OkHttp requests carry `X-POSTHOG-DISTINCT-ID` and `X-POSTHOG-SESSION-ID` when those values are available.

## Set up products

Each product has its own Android setup guide:

- **Error tracking.** See the [error tracking docs](https://posthog.com/docs/error-tracking).
- **Logs.** Follow the [Android logs installation guide](https://posthog.com/docs/logs/installation/android). `PostHog.logger.{trace,debug,info,warn,error,fatal}` sends structured records to [PostHog Logs](https://posthog.com/docs/logs), with batching, offline persistence, and a rate cap built in.
- **Session replay.** Install the SDK, turn on "Record user sessions" in [your project settings](https://us.posthog.com/settings/project-replay), and set the `sessionReplay` option. See [mobile session replay](https://posthog.com/docs/session-replay/mobile).
- **Surveys.** Follow the [installation instructions for Android](https://posthog.com/docs/surveys/installation/android). Surveys with [popover presentation](https://posthog.com/docs/surveys/creating-surveys#presentation) show by themselves to users who match your [display conditions](https://posthog.com/docs/surveys/creating-surveys#display-conditions).
- **Push notifications.** The SDK can register a device for [Workflows](https://posthog.com/docs/workflows) push notifications and capture when a user opens one. [Push notifications](https://posthog.com/docs/workflows/push-notifications) covers automatic and manual registration, capturing opens, opting out, and identity verification.

## Turn on debug mode

Debug mode prints verbose logs about the inner workings of the SDK. Turn it on when you don't see the events, feature flags, surveys, session replay, or error tracking behavior you expect.

Set the `debug` option to `true` in your `PostHogAndroidConfig`:

```kotlin
val config = PostHogAndroidConfig(projectToken = POSTHOG_API_KEY, host = POSTHOG_HOST).apply {
    debug = true
    // ... other config options
}
```

## The configuration options

Pass a `PostHogAndroidConfig` when you create the PostHog client. It inherits the core `PostHogConfig` options and adds Android-specific ones.

```kotlin
import com.posthog.PersonProfiles
import com.posthog.android.PostHogAndroidConfig

val config = PostHogAndroidConfig(
    projectToken = POSTHOG_API_KEY,
    host = POSTHOG_HOST
).apply {
    captureApplicationLifecycleEvents = true
    captureScreenViews = true
    captureDeepLinks = true

    flushAt = 20
    maxQueueSize = 1000
    maxBatchSize = 50
    pushNotificationSubscriptionMaxRetries = 3
    flushIntervalSeconds = 30

    debug = false
    optOut = false

    sendFeatureFlagEvent = true
    featureFlagCalledCacheSize = 1000
    preloadFeatureFlags = true
    evaluationContexts = listOf("production", "android", "mobile")

    setDefaultPersonProperties = true
    personProfiles = PersonProfiles.IDENTIFIED_ONLY
    reuseAnonymousId = false

    sessionReplay = false
    errorTrackingConfig.autoCapture = false
}
```

The Android-specific options:

| Option | Default | Captures |
| --- | --- | --- |
| `captureApplicationLifecycleEvents` | `true` | App lifecycle events |
| `captureScreenViews` | `true` | `$screen` events |
| `captureDeepLinks` | `true` | `Deep Link Opened` events |

The lifecycle events are `Application Installed`, `Application Updated`, `Application Opened`, and `Application Backgrounded`. `$screen` is captured for foreground `android.app.Activity` screens. `Deep Link Opened` carries URL, query, and referrer properties.

The core options:

| Option | Default | Sets |
| --- | --- | --- |
| `debug` | `false` | Verbose SDK logs in Logcat |
| `optOut` | `false` | No data capture |
| `flushAt` | `20` | Queued events that trigger a flush |
| `maxQueueSize` | `1000` | Events kept in memory and on disk |
| `maxBatchSize` | `50` | Events in one batch request |
| `pushNotificationSubscriptionMaxRetries` | `3` | Retries for push subscription registration |
| `flushIntervalSeconds` | `30` | Longest wait before a flush |
| `captureAiCompression` | `PostHogCompression.GZIP` | Compression for `captureAi` requests |
| `captureAiMaxQueueSize` | `100` | `captureAi` events kept on disk |
| `captureAiTimeout` | `60` | Seconds before a `captureAi` request times out |
| `captureAiMaxEventBytes` | `8454144` | Size of one `captureAi` event |
| `encryption` | `null` | Encryption for queued events |
| `proxy` | `null` | A `java.net.Proxy` for API requests |
| `getAnonymousId` | generated UUID | Your own anonymous ID |
| `reuseAnonymousId` | `false` | One anonymous ID across users |
| `personProfiles` | `PersonProfiles.IDENTIFIED_ONLY` | When person profiles are processed |
| `setDefaultPersonProperties` | `true` | Default properties for flag requests |
| `releaseIdentifier` | app and version fallback | Release ID for error tracking |
| `tracingHeaders` | `null` | Hostnames that get tracing headers |

Some of these need more detail:

- **`debug` and `optOut`.** You can also change them at runtime, with `PostHog.debug(true)`, `PostHog.optOut()`, and `PostHog.optIn()`.
- **`maxQueueSize`.** Past the limit, the oldest events are removed first.
- **`pushNotificationSubscriptionMaxRetries`.** It doesn't apply to event, session recording, or log ingestion.
- **The `captureAi` options.** See [Capture AI events](#capture-ai-events).
- **`encryption`.** It takes a `PostHogEncryption` implementation that encrypts the queued events on disk.
- **`reuseAnonymousId`.** One anonymous ID is reused across user changes on the same device.
- **`personProfiles`.** The values are `IDENTIFIED_ONLY`, `ALWAYS`, and `NEVER` (see [Choose person profiles](#choose-person-profiles)).
- **`setDefaultPersonProperties`.** It adds default device and app properties to feature flag evaluation requests.
- **`releaseIdentifier`.** Error tracking and uploaded ProGuard and R8 mappings use it. The Android Gradle plugin can inject it for you.
- **`tracingHeaders`.** Hostnames are matched exactly, and only with `PostHogOkHttpInterceptor` (see [Add tracing headers](#add-tracing-headers)).

The feature flag options:

| Option | Default | Sets |
| --- | --- | --- |
| `sendFeatureFlagEvent` | `true` | `$feature_flag_called` on each evaluation |
| `featureFlagCalledCacheSize` | `1000` | Flag calls cached for deduplication |
| `preloadFeatureFlags` | `true` | Flags fetched during setup |
| `evaluationContexts` | `null` | Context tags that limit which flags evaluate |

To run code when flags load, call `PostHog.onFeatureFlags()` (see [Wait for flags](#wait-for-flags)).

The product options:

| Option | Default | Sets |
| --- | --- | --- |
| `sessionReplay` | `false` | Session replay |
| `sessionReplayConfig` | `PostHogSessionReplayConfig()` | Replay capture options |
| `logs` | `PostHogLogsConfig()` | [Android logs](https://posthog.com/docs/logs/installation/android) |
| `errorTrackingConfig` | `PostHogErrorTrackingConfig()` | Error tracking |
| `surveys` | `false` | Native Android surveys |
| `surveysConfig` | `PostHogSurveysConfig()` | The survey display delegate |
| `bootstrap` | `null` | Seeded identity and flags |

- **`sessionReplay`.** Replay records only when your project settings also allow recording.
- **`sessionReplayConfig`.** It configures masking, Logcat capture, and sampling.
- **`errorTrackingConfig`.** `autoCapture` defaults to `false`. Set it to `true` to capture uncaught exceptions when your project settings also turn on error tracking.
- **`surveys` and `surveysConfig`.** They're internal and experimental, mainly for hybrid SDKs. Native Android survey UI isn't fully supported or documented yet.
- **`bootstrap`.** See [Bootstrap feature flags](#bootstrap-feature-flags).

## Filter events

Use `addBeforeSend` to redact, change, or drop events before they're queued. Return `null` to drop an event.

```kotlin
config.addBeforeSend { event ->
    event.properties?.remove("password")

    if (event.event == "internal_debug_event") {
        null
    } else {
        event
    }
}
```

The same hook stops specific screens from being autocaptured. Return `null` for any `$screen` event whose `$screen_name` matches a screen you don't want to track, and it's dropped before it's sent.

It's a function, so you can filter any way you like. Use an ignorelist to drop the screens you name, or invert the check for an allowlist that captures only the screens you name. You can also write a custom rule, such as a name prefix, a regex, or a check on the event's properties.

```kotlin
val ignoredScreens = setOf("Splash", "Debug")

config.addBeforeSend { event ->
    val screenName = event.properties?.get("\$screen_name") as? String
    if (event.event == "\$screen" && screenName in ignoredScreens) {
        null
    } else {
        event
    }
}
```

## The platform requirements

The Android SDK supports Android API 23 and newer.

You usually don't need to declare permissions in your `AndroidManifest.xml`. The SDK declares `android.permission.INTERNET` and `android.permission.ACCESS_NETWORK_STATE`, and Android's manifest merger adds them to your app. The SDK doesn't declare or need an Android `Service`.

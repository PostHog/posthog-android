[![GH Workflow](https://img.shields.io/github/actions/workflow/status/PostHog/posthog-android/build.yml?branch=main)](https://github.com/PostHog/posthog-android/actions)

| Packages        | Maven Central                                                                                                                                                                                  | Min Version    |
|-----------------| ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------- |
| posthog-android | [![Maven Central](https://maven-badges.herokuapp.com/sonatype-central/com.posthog/posthog-android/badge.svg)](https://maven-badges.herokuapp.com/sonatype-central/com.posthog/posthog-android) | Android API 21 |
| posthog-android-surveys-compose | [![Maven Central](https://maven-badges.herokuapp.com/sonatype-central/com.posthog/posthog-android-surveys-compose/badge.svg)](https://maven-badges.herokuapp.com/sonatype-central/com.posthog/posthog-android-surveys-compose) | Android API 23 · `0.x` |
| posthog (core)  | [![Maven Central](https://maven-badges.herokuapp.com/sonatype-central/com.posthog/posthog/badge.svg)](https://maven-badges.herokuapp.com/sonatype-central/com.posthog/posthog)                 | Java 8         |
| posthog-server  | [![Maven Central](https://maven-badges.herokuapp.com/sonatype-central/com.posthog/posthog-server/badge.svg)](https://maven-badges.herokuapp.com/sonatype-central/com.posthog/posthog-server)                 | Java 8         |
| posthog-android-gradle-plugin  | [![Maven Central](https://maven-badges.herokuapp.com/sonatype-central/com.posthog/posthog-android-gradle-plugin/badge.svg)](https://maven-badges.herokuapp.com/sonatype-central/com.posthog/posthog-android-gradle-plugin)                 | Java 8         |

# PostHog Android and JVM SDKs

This repository contains PostHog's Android and JVM SDKs. PostHog is an open source platform for product analytics, feature flags, session replay, and more.

Please see the main [PostHog docs](https://posthog.com/docs).

SDK usage examples and code snippets live in the official documentation so they stay up to date.

## Documentation

- [Android library docs](https://posthog.com/docs/libraries/android)
- [Java/JVM library docs](https://posthog.com/docs/libraries/java)

## Required dependencies

`posthog-android` needs [Curtains](https://github.com/square/curtains) (`com.squareup.curtains:curtains`) at runtime. The SDK reads window and touch events through it.

Do not exclude that artifact from your build. Without it, session replay, interaction autocapture and touch tracking stay off for the whole process. The SDK writes a warning to Logcat when this happens.

## Server span context

`posthog-server` can add `sessionId` and `posthogDistinctId` attributes to recording OpenTelemetry SDK spans. To turn this on, register `com.posthog.server.otel.PostHogContextSpanProcessor` on the application's existing provider. Then open each request scope with the same PostHog client.

Spans get IDs only from a scope owned by that client. A disabled or opted-out client adds no IDs. An outermost scope opened without a client adds no span IDs. A nested scope opened without a client keeps the owner of the outer scope.

When a client-owned scope opens, it also adds IDs to the current active span. This happens when that client has a registered processor on any provider.

The processor holds its client weakly. If the application closes its client and makes a new one, build a new processor and provider for the new client.

```kotlin
val posthog = PostHog.with(config)

val tracerProvider = SdkTracerProvider.builder()
    .addSpanProcessor(PostHogContextSpanProcessor(posthog))
    .build()

PostHogRequestContext.withContext(posthog, PostHogRequestContext.fromHeaders(headers), fresh = true) {
    handleRequest()
}
```

OpenTelemetry SDK 1.32.0 or later remains an optional application dependency. Existing canonical attributes take precedence, including attributes with other value types. The bridge preserves the application's sampling and export setup.

Use a fresh request scope and close it on the same thread. Work on another thread needs its own context scope. Automatic coroutine, Reactor, and framework propagation require application setup. See the [Java tracing guide](https://posthog.com/docs/distributed-tracing/installation/java) for setup.

## Questions?

### [Check out our community page.](https://posthog.com/posts)

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for local setup and test instructions.

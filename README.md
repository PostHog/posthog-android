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

### Warm deep links on Android

With `captureDeepLinks` enabled, activities using `singleTop` or `singleTask` must call
`setIntent(intent)` in `onNewIntent`, after `super.onNewIntent(intent)`, to make the new intent
available for automatic `Deep Link Opened` capture on resume. No additional PostHog API call is
needed. A new Intent with the same URL is captured again; resuming with the same Intent is not.
Mutating an existing Intent in place is not treated as a new delivery. Capture on activity
creation (including recreation) remains unchanged. Push-open tracking still uses its existing
[notification integration](https://posthog.com/docs/libraries/android).

## Questions?

### [Check out our community page.](https://posthog.com/posts)

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for local setup and test instructions.

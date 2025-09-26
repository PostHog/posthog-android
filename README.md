[![GH Workflow](https://img.shields.io/github/actions/workflow/status/PostHog/posthog-android/build.yml?branch=main)](https://github.com/PostHog/posthog-android/actions)

| Packages        | Maven Central                                                                                                                                                                                  | Min Version    |
| --------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------- |
| posthog-android | [![Maven Central](https://maven-badges.herokuapp.com/sonatype-central/com.posthog/posthog-android/badge.svg)](https://maven-badges.herokuapp.com/sonatype-central/com.posthog/posthog-android) | Android API 21 |
| posthog (core)  | [![Maven Central](https://maven-badges.herokuapp.com/sonatype-central/com.posthog/posthog/badge.svg)](https://maven-badges.herokuapp.com/sonatype-central/com.posthog/posthog)                 | Java 8         |

# PostHog Android & JVM SDKs

This repository contains PostHog's Android and JVM SDKs. PostHog is an open source platform for product analytics, feature flags, session replay, and more.

## Packages

### posthog-android

Full-featured Android SDK with automatic screen tracking, session recording, and Android-specific features.

```kotlin
implementation("com.posthog:posthog-android:$latestVersion")
```

**Documentation:** [posthog-android/](./posthog-android/) | **Usage:** [posthog-android/USAGE.md](./posthog-android/USAGE.md)

### posthog (core)

Pure Kotlin/JVM library suitable for environment specific SDK integrations.

```kotlin
implementation("com.posthog:posthog:$latestVersion")
```

**Documentation:** [posthog/](./posthog/) | **Usage:** [posthog/USAGE.md](./posthog/USAGE.md)

## Documentation

Please see the main [PostHog docs](https://posthog.com/docs).

Specifically, the [Android docs](https://posthog.com/docs/libraries/android) details.

## Questions?

### [Check out our community page.](https://posthog.com/posts)

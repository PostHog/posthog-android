---
"posthog": minor
"posthog-android": minor
"posthog-server": minor
---

- chore: Upgrade to Kotlin 2.1.10, AGP 8.9.1, Gradle 8.12, compileSdk 36, minSdk 23
- chore: Upgrade dependencies (OkHttp 4.12.0, Robolectric 4.14.1, Dokka 1.9.20, Kover 0.9.0)
- fix: Remove duplicate default parameter values in interfaces for Kotlin 2.x compatibility
- fix: Increase MockWebServer takeRequest timeout to fix flaky CI test

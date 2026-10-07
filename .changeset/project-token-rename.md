---
'posthog': minor
'posthog-android': minor
'posthog-server': minor
'posthog-android-surveys-compose': patch
---

Add `projectToken` to `PostHogConfig` and `PostHogAndroidConfig` and deprecate `apiKey` (Kotlin subclasses calling `super(apiKey = ...)` must switch to `projectToken =`)

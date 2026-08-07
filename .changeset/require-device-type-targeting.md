---
'posthog': minor
'posthog-android': minor
---

Add opt-in `PostHogSurveysConfig.requireDeviceTypeTargeting` to exclude surveys without explicit device-type targeting on Android. When enabled, surveys with missing or empty `conditions.deviceTypes` are ineligible; surveys with a non-empty device-type condition continue to use the existing match operators against `Mobile`, `Tablet`, or `TV`.

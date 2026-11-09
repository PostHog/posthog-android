---
"posthog-android": patch
---

Drop native crashes recorded while the SDK was opted out instead of sending them after `optIn()`. `PostHogNativeCrashIntegration` is now always registered so consent changes are recorded; it still scans only when `captureNativeCrashes` is enabled.

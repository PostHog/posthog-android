---
"posthog": minor
"posthog-android": minor
---

Add native (NDK) crash capture on Android 12+, opt-in via `errorTrackingConfig.captureNativeCrashes`. On startup the SDK reads the native crash records the OS kept (`ApplicationExitInfo` tombstones) and captures an `$exception` event per crash with raw native stack frames and `$debug_images`, so PostHog symbolicates them against `.so` debug symbols uploaded with `posthog-cli symbol-sets upload`.

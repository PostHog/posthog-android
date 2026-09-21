---
"posthog-android-gradle-plugin": minor
---

Add `skipOnConflict` and `force` properties on `PostHogUploadProguardMappingsTask`. When set, the proguard mapping upload passes `--skip-on-conflict` or `--force` to `posthog-cli` (>= 0.7.12). The two properties cannot be set together.

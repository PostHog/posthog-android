---
'posthog-android-gradle-plugin': minor
---

Remove the `posthog.releaseMode` gradle property and the `POSTHOG_RELEASE_MODE` environment variable. The plugin uploads the proguard mapping bound to the release it creates, which is what it did before the property existed.

Event mode only helps when two releases ship a byte-identical mapping. The map id is already a content hash, so an ordinary release that changes code gets its own symbol set and never collides with an earlier one. The property was experimental and undocumented, so it goes out directly.

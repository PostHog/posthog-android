---
"posthog-android": patch
---

Fix a manually configured `releaseIdentifier` being overwritten by the auto-generated fallback (`applicationId@versionName+versionCode`), which broke proguard symbolication due to the map-id mismatch. A pre-set `releaseIdentifier` is now preserved; the value from `posthog-meta.properties` is used when nothing was set, and the fallback only when neither exists.

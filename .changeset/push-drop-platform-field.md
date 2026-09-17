---
"posthog": patch
"posthog-android": patch
---

Stop sending the unused `platform` field when registering a device for push notifications. The API resolves the provider from `app_id` alone and ignores `platform`, so the field was dead weight. It was also the only field in the request without `@SerializedName`, which meant R8 renamed it in minified release builds. Removing it drops that failure mode instead of working around it.

---
"posthog": patch
"posthog-android": patch
---

Fix device push registration being rejected in minified release builds. `PostHogPushSubscriptionRequest.platform` had no `@SerializedName`, and the class had no R8 keep rule, so the field was renamed and the server rejected the request with `400 missing_fields`. Affected devices never registered, so push notifications silently did not work for them.

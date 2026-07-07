---
"posthog-android": patch
---

Fix session replay dropping screen captures that fall inside a Throttler window but are not themselves throttled: the Throttler now always forwards the first event in a new window even when the per-second rate cap is reached, so no screens are silently skipped.

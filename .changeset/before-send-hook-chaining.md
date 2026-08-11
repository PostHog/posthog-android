---
"posthog": patch
"posthog-server": patch
---

Fix `beforeSend` hook chaining in `PostHogStateless.buildEvent()`: when multiple hooks are registered, each hook now receives the previous hook's output instead of the original, pre-chain event, so a mutation made by one hook is visible to the next. A hook that throws no longer drops the event entirely — the event falls back to the last good (pre-exception) value instead, matching the documented per-SDK fallback policy. Affects both `posthog-android` and `posthog-server`, which share this code path.

---
"posthog": patch
"posthog-android": patch
"posthog-server": patch
---

Fix `beforeSend` hook chaining in `PostHogStateless.buildEvent()`: when multiple hooks are registered, each hook now receives the previous hook's output instead of the original, pre-chain event, so a mutation made by one hook is visible to the next. Hooks that throw continue to drop the event to avoid enqueueing a potentially unsafe, partially processed payload. Affects both `posthog-android` and `posthog-server`, which share this code path.

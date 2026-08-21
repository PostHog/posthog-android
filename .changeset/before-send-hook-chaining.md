---
"posthog": patch
"posthog-android": patch
"posthog-server": patch
---

Fix `beforeSend` hook chaining so each hook receives the previous hook's output.

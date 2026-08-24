---
"posthog-android": patch
---

Fix: reload feature flags when the app returns to the foreground. Flag-gated session replay now starts for a returning user whose linked flag turned on while the app was backgrounded (a wider rollout, or person-property targeting the server resolves later), instead of staying disabled until the next cold start or `identify()`.

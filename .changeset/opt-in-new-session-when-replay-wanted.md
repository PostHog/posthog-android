---
"posthog": patch
"posthog-android": patch
---

Start a new session on `optIn()` whenever session replay was wanted at opt-out, even if a linked flag or unloaded remote config keeps replay from starting yet.

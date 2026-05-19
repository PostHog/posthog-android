---
"posthog": patch
"posthog-android": patch
"posthog-server": patch
---

Refactor `PostHogQueue` to be generic on `Record` and introduce `EndpointSpec`
for per-endpoint codec, send, retry policy, and runtime knobs. No behavior
change for events or session replay; sets up future log-record support without
duplicating queue plumbing.

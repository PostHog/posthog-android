---
"posthog": patch
"posthog-android": patch
---

Stop sending `$sdk_debug_replay_*` and `$sdk_debug_session_start` on events while session replay is intentionally off (disabled in config, stopped by the app, or turned off remotely). `$recording_status` is still sent as `disabled`. Sampled-out and trigger-pending sessions are unchanged.

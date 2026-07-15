---
"posthog": patch
"posthog-android": patch
---

Don't overwrite a returning user's persisted identity when `setup()` runs in Direct Boot mode. While credential encrypted storage is locked, the persisted `anonymousId`/`deviceId` are unreadable, so the get-or-create getters minted fresh ids and buffered them — and the buffered write clobbered the real ids on the first access after unlock. `PostHogPreferences` now exposes `isAvailable()`, and ids generated while the store is unavailable stay transient (in memory only): after unlock, a previously persisted identity wins; on a fresh install the transient id is persisted on first use.

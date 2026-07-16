---
"posthog": patch
"posthog-android": patch
---

Don't let state read while the preferences store is temporarily unreadable (Direct Boot, before the first unlock) overwrite or shadow persisted values. `PostHogPreferences` now exposes `isAvailable()`, and while it is false:

- auto-generated `anonymousId`/`deviceId` stay transient (in memory only) — after unlock a previously persisted identity wins; on a fresh install the transient id is persisted on first use
- the `isIdentified` and person-processing fallbacks are neither persisted nor cached, so the persisted values are re-resolved once the store unlocks
- the persisted opt-out choice is resolved lazily on the capture path once the store is readable (gating on the `config.optOut` default until then) instead of being baked in at `setup()` — this also fixes a latent bug where the setup-time read consulted the in-memory fallback store and never honored a persisted opt-out
- the Android app-install integration defers install/update detection instead of firing a spurious `Application Installed` for an existing install and overwriting the stored previous version

Behavior change: `group()` now early-returns when the user is opted out, so it no longer registers `$groups` or reloads feature flags in that case (previously only the downstream `$groupidentify` event was suppressed). This is required for the lazy opt-out gating above to honor a persisted opt-out, and matches `posthog-ios`.

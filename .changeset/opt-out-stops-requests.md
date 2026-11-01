---
"posthog": patch
---

Stop all requests to PostHog while opted out. Feature flag reloads from `identify()`, `reset()`, `group()` and `reloadFeatureFlags()`, and the setup remote config load, no longer send the distinct and device IDs. The flags callback still fires. Events queued before `optOut()` stay on disk and upload only after `optIn()`, which also loads remote config and feature flags.

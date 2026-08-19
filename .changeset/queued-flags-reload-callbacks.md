---
"posthog": patch
---

Carry a displaced caller's callbacks forward when a queued feature flag reload is replaced. `executeFeatureFlags` keeps one pending reload behind the in-flight one, and a third reload overwrote that slot wholesale, so the displaced request's `onFeatureFlags` was never invoked. This is latent rather than user-visible — every executor the SDK uses is single-threaded and `api.flags` blocks on that thread, so two reloads cannot overlap on a stock configuration — but the queuing machinery now honours the contract it was written for.

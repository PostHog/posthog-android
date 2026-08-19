---
"posthog": patch
---

Carry a displaced caller's callbacks forward when a queued feature flag reload is replaced. `executeFeatureFlags` keeps one pending reload behind the in-flight one, and a third reload overwrote that slot wholesale, so the displaced request's `onFeatureFlags` was never invoked. Also closes a second way a queued reload could be stranded: the in-flight claim and the pending slot were guarded by different mechanisms, so a reload could queue itself just after the in-flight request had drained an empty queue, leaving it with nothing to execute it. Both are latent — every executor the SDK constructs is single-threaded and `api.flags` blocks on that thread, so two reloads cannot overlap unless a host supplies its own pooled executor through `remoteConfigProvider`.

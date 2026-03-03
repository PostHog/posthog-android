---
"posthog-server": patch
---

Add semver comparison operators to local feature flag evaluation

This adds 9 semver operators for targeting users based on app version:
- `semver_eq`, `semver_neq` — exact match / not equal
- `semver_gt`, `semver_gte`, `semver_lt`, `semver_lte` — comparison operators
- `semver_tilde` — patch-level range (~1.2.3 means >=1.2.3 <1.3.0)
- `semver_caret` — compatible-with range (^1.2.3 means >=1.2.3 <2.0.0)
- `semver_wildcard` — wildcard range (1.2.* means >=1.2.0 <1.3.0)

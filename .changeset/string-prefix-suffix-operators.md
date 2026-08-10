---
"posthog-server": minor
"posthog": minor
---

Support the `starts_with`, `not_starts_with`, `ends_with`, and `not_ends_with` property-filter operators in local feature flag evaluation. Both the property value and filter value are stringified and case-folded before the prefix/suffix comparison (mirroring `icontains`); the `not_*` variants negate the result. Flags targeting on these operators previously could not be evaluated locally and always fell back to remote evaluation.

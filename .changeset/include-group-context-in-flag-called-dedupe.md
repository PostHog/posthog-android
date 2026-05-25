---
"posthog": patch
"posthog-server": patch
---

Include group context in the `$feature_flag_called` LRU dedupe key so group-scoped flags fire a separate event for each group a user is evaluated under, instead of being dedup-ed against the first group context the same `(distinctId, flagKey, value)` was seen under. The groups are canonicalized order-independently so two equal maps built in different insertion orders still dedupe to one event.

---
'posthog-server': minor
---

`evaluateFlags` now keeps whatever local evaluation resolved and asks `/flags` only for the keys it could not resolve. Previously a single inconclusive flag definition discarded the whole locally-computed batch, so one flag gated on a property the caller does not pass forced a request per identity, and a `/flags` outage turned locally-resolvable flags off. `flagKeys` now scopes local evaluation as well as the request, and a requested key with no local definition never forces a request on its own: it is absent from the snapshot, unless an unresolved flag already required the `/flags` call, which then also fills it. `onlyEvaluateLocally = true` is now strictly local: it never serves cached remote values, so flags it cannot resolve are omitted. A group-aggregated flag evaluated without its group key still resolves locally to `false`, and that value now takes precedence over the server's, so pass `groups` when gating on group flags.

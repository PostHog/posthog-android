---
'posthog-server': minor
---

- Move `PostHogOnFeatureFlags` into `posthog-server`; its API is unchanged
- Bump the `posthog` core dependency to 7.0
- Disable the client at setup when `projectToken` is a personal API key (`phx_...`), as for an empty token

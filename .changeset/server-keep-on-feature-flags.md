---
'posthog-server': minor
---

- Move `PostHogOnFeatureFlags` into `posthog-server`; its API is unchanged
- Bump the `posthog` core dependency to 7.0
- Disable the client at setup when `projectToken` is a personal API key (`phx_...`), as for an empty token, and print why for both even when `debug` is off
- Drop events with an empty name before sending
- Merge `userProperties`, `userPropertiesSetOnce` and `groups` key by key over a `$set`, `$set_once` or `$groups` map in `properties`

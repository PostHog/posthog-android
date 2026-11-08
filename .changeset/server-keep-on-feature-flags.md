---
'posthog-server': minor
---

- Bump the `posthog` core dependency to 7.0; `posthog-server` keeps its API, `/batch` endpoint and retry behavior
- Move `PostHogOnFeatureFlags` into `posthog-server` with the same name and API
- Add an `options` property to `PostHogEvent`, which `beforeSend` can read; `posthog-server`'s `/batch` endpoint ignores it
- Disable the client at setup when `projectToken` is a personal API key (`phx_...`), as for an empty token, and print why for both even when `debug` is off
- Drop events with an empty name before sending them
- Merge `userProperties`, `userPropertiesSetOnce` and `groups` key by key over a `$set`, `$set_once` or `$groups` map in `properties`
- Stop printing unserializable property values and `beforeSend` exception messages in debug logs
- Fix losing an event whose properties hold a nested map with non-string keys; number, boolean and `null` keys are converted to strings
- Fix `beforeSend` edits reaching the caller's nested maps and lists, by giving each hook its own copy

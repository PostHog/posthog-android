---
'posthog-server': minor
---

- Require `posthog-android` 4.0.0 or later in the same project, since core `posthog` 7.0.0 makes Gradle upgrade `posthog-android` 3.x.x to 4.0.0. Stay on `posthog-server` 2.24.x until you upgrade (see the [migration guide](https://github.com/PostHog/posthog-android/blob/main/MIGRATION.md#upgrade-posthog-server))
- Bump the `posthog` core dependency to 7.0.0. `posthog-server` keeps its API, `/batch` endpoint and retry behavior
- Move `PostHogOnFeatureFlags` into `posthog-server` with the same name and API
- Add an `options` property to `PostHogEvent`, which `beforeSend` can read. `posthog-server` still sends to `/batch`, which doesn't use it, so setting it in `beforeSend` has no effect
- Disable the client at setup when `projectToken` is a personal API key (`phx_...`), as for an empty token, and print why for both even when `debug` is off
- Drop events with an empty name before sending them
- Merge `userProperties`, `userPropertiesSetOnce` and `groups` key by key over a `$set`, `$set_once` or `$groups` map in `properties`
- Stop printing unserializable property values and `beforeSend` exception messages in debug logs
- Fix losing an event whose properties hold a nested map with non-string keys. Number, boolean and `null` keys are converted to strings
- Fix removing `null` elements from lists and arrays in event properties, which moved later elements to lower indexes. They're sent as JSON `null` at their position, as is an element that can't be serialized
- Fix `beforeSend` edits reaching the caller's nested maps and lists, by giving each hook its own copy

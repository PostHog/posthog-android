---
'posthog-server': patch
---

Drop events with an empty name before sending, merge `userProperties`, `userPropertiesSetOnce` and `groups` key by key over a `$set`, `$set_once` or `$groups` map in `properties`, and print why the client is disabled for an empty or personal API key `projectToken` even when `debug` is off

---
"posthog": minor
"posthog-android": minor
---

Support session replay v2 trigger groups from remote config

Session replay sampling now handles negative hashes like posthog-js, so a session gets the same sampling decision on mobile and web

Session replay event triggers now match the event after the `beforeSend` hooks, like posthog-ios and posthog-js: an event a hook drops no longer starts recording, and a renamed event matches under its new name

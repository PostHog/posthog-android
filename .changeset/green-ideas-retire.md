---
"posthog-android-surveys-compose": minor
---

Raise the minimum Material3 version to 1.4.0 and use bottom-sheet APIs compatible with Material3 1.5.0. Isolate survey composition and measurement failures so incompatible Compose dependencies skip the survey instead of crashing the host app.

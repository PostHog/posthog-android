---
"posthog-android": patch
---

Fix automatic deep-link capture for warm `singleTop` and `singleTask` activity launches. Call `setIntent(intent)` in `onNewIntent` so the SDK can capture the new intent on resume. Repeated resumes do not duplicate the event, while distinct intents with the same URL are captured separately.

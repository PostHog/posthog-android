---
"posthog": patch
"posthog-android": patch
---

Include survey responses on Android dismissal events, including question id based response keys and partial completion state. Null rating responses are ignored instead of being serialized as "null".

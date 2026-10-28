---
"posthog-android": patch
---

Session replay now masks Jetpack Compose images that have no content description, such as `Image` or Coil `AsyncImage` with `contentDescription = null`, when `maskAllImages` is enabled.

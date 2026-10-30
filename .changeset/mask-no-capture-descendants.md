---
"posthog-android": patch
---

Session replay now masks the children of a `ph-no-capture` view, so content that draws outside an unclipped container is no longer recorded. While one of those children is animating, the snapshot may be skipped.

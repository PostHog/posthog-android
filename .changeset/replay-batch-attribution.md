---
"posthog": patch
"posthog-android": patch
---

Split session replay uploads at session or distinct ID changes so queued snapshots retain their original attribution. Send boundary-separated groups sequentially within the existing batch limit, preserving failed and unsent snapshots for retry.

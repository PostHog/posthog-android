---
"posthog-android": patch
---

Fix manually started session replay being stopped by a queued session-rotation callback when automatic replay is disabled with `sessionReplay = false`.

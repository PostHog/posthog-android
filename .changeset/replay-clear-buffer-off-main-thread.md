---
"posthog-android": patch
---

Session Replay: fix a main-thread ANR triggered when the session id rotates (e.g. on `identify()` at login) while replay is active. The buffer clear on session reset no longer blocks the caller's thread waiting on the single-threaded replay executor; it is now scheduled fire-and-forget, so a busy executor (mid snapshot disk IO) can't stall the UI thread.

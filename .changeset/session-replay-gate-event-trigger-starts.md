---
"posthog": patch
"posthog-android": patch
---

Fix: a session recording started by an event trigger now checks the same gates as every other start path. A matching event used to start recording even when `PostHogConfig.sessionReplay` was false, the project flag was off, or sampling excluded the session, so an app that gates replay behind its own feature flag recorded the users the flag excluded. A manual start can still wait for a matching event, and `PostHog.stopSessionReplay` cancels that pending request.

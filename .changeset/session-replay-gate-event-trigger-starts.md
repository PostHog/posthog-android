---
"posthog": patch
"posthog-android": patch
---

Fix: a session recording started by an event trigger now checks the same gates as every other start path. A matching event used to start recording even when `PostHogConfig.sessionReplay` was false, the project flag was off, or sampling excluded the session, so an app that gates replay behind its own feature flag recorded the users the flag excluded. The recording was also treated as manually started, so no later check stopped it. A recording that `PostHog.startSessionReplay` asked for while automatic replay is off still starts when the trigger matches.

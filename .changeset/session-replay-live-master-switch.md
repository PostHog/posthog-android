---
"posthog": patch
"posthog-android": patch
---

Fix: writing `PostHogConfig.sessionReplay` after setup now takes effect right away. It used to be honored only at setup, and then again at the next session rotation or remote config delivery, so an app that read its own feature flag and assigned the result kept recording a user the flag excluded. Setting it to false stops recording; setting it to true resumes it when the project settings, the linked flag, the event triggers, and sampling also allow it.

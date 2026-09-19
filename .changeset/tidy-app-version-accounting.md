---
"posthog-android": patch
---

Remember each project's app version and build even when lifecycle events are disabled or suppressed, including version-name changes with an unchanged build, so later launches report the correct previous version without false Application Installed events.

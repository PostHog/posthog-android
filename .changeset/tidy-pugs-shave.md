---
"posthog-android": patch
"posthog": patch
---

Report a missing runtime dependency instead of disabling an integration in silence. When an integration cannot be built or installed because a class is absent, the SDK now logs a warning that names the Maven artifact the build excludes, even when debug is off, and keeps the other integrations. A build that excludes `com.squareup.curtains:curtains` turns off session replay, interaction autocapture and touch tracking, and said nothing useful about it before.

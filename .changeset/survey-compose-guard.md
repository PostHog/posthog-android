---
"posthog-android-surveys-compose": patch
---

The survey no longer crashes the host app when the app's Compose or Material 3 version is incompatible, for example Material 3 1.5. The SDK now catches the error from the first composition, logs it, and closes the survey, so the survey does not show again on the next launch.

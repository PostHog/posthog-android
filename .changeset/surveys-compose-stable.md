---
"posthog-android-surveys-compose": major
---

Release `posthog-android-surveys-compose` as stable 1.0. The public API (`PostHogSurveysComposeDelegate`) is now covered by binary-compatibility validation and follows semantic versioning. The module's API and survey UI are unchanged from 0.5.1, but 1.0.0 depends on `posthog-android` 4.x and core `posthog` 7.x instead of 3.x and 6.x. Apps still on `posthog-android` 3.x must follow [MIGRATION.md](https://github.com/PostHog/posthog-android/blob/main/MIGRATION.md) when upgrading.

---
"posthog-android": minor
---

Re-publish `posthog-android` so it resolves `posthog` 6.34.0, which carries the survey intro screen appearance fields (`displayIntroScreen`, `introScreenHeader`, `introScreenDescription`, `introScreenDescriptionContentType`, `introScreenButtonText`). `posthog-android` exposes the core SDK via `api`, so custom survey delegates consuming `PostHogDisplaySurveyAppearance` through this artifact could not see the new fields while its published dependency stayed pinned to 6.33.7.

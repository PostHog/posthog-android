---
"posthog-android": patch
"posthog-android-surveys-compose": patch
---

Fix repeat trigger events restarting a survey's `surveyPopupDelaySeconds` delay; a custom `PostHogSurveysDelegate` that can't show a survey must now call `onSurveyClosed`

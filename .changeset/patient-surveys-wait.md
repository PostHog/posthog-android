---
"posthog-android": patch
"posthog-android-surveys-compose": patch
---

Fix repeat trigger events restarting a survey's `surveyPopupDelaySeconds` delay; a custom `PostHogSurveysDelegate` that can't show a survey must now call `onSurveyClosed`. Flutter apps should use posthog_flutter 5.50.13 or later, since earlier versions can stop showing surveys until the app restarts.

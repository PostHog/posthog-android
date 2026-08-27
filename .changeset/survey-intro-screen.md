---
"posthog": minor
"posthog-android-surveys-compose": minor
---

Surveys can now display an optional intro screen before the first question, configured via the new `displayIntroScreen`, `introScreenHeader`, `introScreenDescription`, `introScreenDescriptionContentType`, and `introScreenButtonText` appearance fields (mirroring the existing thank-you message fields, including translations). The fields are exposed on `SurveyAppearance` and `PostHogDisplaySurveyAppearance` for custom survey delegates, and the Compose UI module renders the intro screen natively. Advancing past the intro records no response and sends no survey event; dismissing the survey from the intro still sends the normal `survey dismissed` event.

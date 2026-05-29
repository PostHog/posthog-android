# posthog-android-surveys-compose changelog

## 1.0.0-alpha01

Initial alpha release of the optional Compose-based default UI for PostHog
Android surveys. Customers opt in by adding this artifact alongside
`com.posthog:posthog-android` and wiring the delegate once at SDK init:

```kotlin
config.surveysConfig.surveysDelegate =
    PostHogSurveysComposeDelegate(applicationContext)
```

### Scope

- All question types: NPS / Number Rating (0–10, 1–5, 1–7 scales), Emoji
  rating (3 and 5 face), Open text, Single choice, Multiple choice, Link
- Material 3 `ModalBottomSheet` container with sticky close button
- Multi-question surveys advance one question at a time
- Thank-you screen (`ConfirmationScreen`) shown when the customer has
  `displayThankYouMessage` enabled in PostHog
- Theming sourced from `PostHogDisplaySurveyAppearance` (background, submit
  button, text, border, rating, placeholder, thank-you copy) with a faithful
  port of the iOS hex / CSS-color name parser
- `onSurveyShown` / `onSurveyResponse` / `onSurveyClosed` lifecycle callbacks
  wired so the core SDK fires `survey shown` / `survey sent` / `survey
  dismissed` events
- `@Preview` composables for each component (default + themed) for visual
  review in Android Studio

### Explicitly out of scope (planned follow-ups)

- Server-driven branching logic — the sheet always advances to
  `currentQuestionIndex + 1` until the host SDK reports completion
- Event dispatch directly from the delegate (today the host SDK fires
  events via the lifecycle callbacks our delegate invokes)
- HTML descriptions (question + thank-you) — rendered as plain text only,
  matching iOS
- Compose UI tests and accessibility audit
- Dark-mode polish

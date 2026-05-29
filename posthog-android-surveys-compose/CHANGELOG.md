# posthog-android-surveys-compose changelog

## 1.0.0-alpha01

Initial alpha release of the optional Compose-based default UI for PostHog
Android surveys. Customers opt in by adding this artifact alongside
`com.posthog:posthog-android` and wiring the delegate once at SDK init:

```kotlin
config.surveysConfig.surveysDelegate =
    PostHogSurveysComposeDelegate(applicationContext)
```

### MVP scope (this release)

- NPS / Number Rating question type (0–10, 1–5, 1–7 scales)
- Material 3 `ModalBottomSheet` container
- Theming sourced from `PostHogDisplaySurveyAppearance` (background, submit
  button, text, border, rating button colors) with a faithful port of the
  iOS hex / CSS-color name parser
- `onSurveyShown` / `onSurveyResponse` / `onSurveyClosed` lifecycle callbacks
  wired so the core SDK fires `survey shown` / `survey sent` / `survey
  dismissed` events
- `@Preview` composables for visual review in Android Studio

### Explicitly out of scope (planned follow-ups)

- Open text, single-choice, multiple-choice, and link question types
- Emoji rating
- Thank-you / confirmation screen
- Branching logic verification across multi-question surveys
- Dark-mode polish
- Compose UI tests and accessibility audit

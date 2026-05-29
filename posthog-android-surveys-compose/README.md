# posthog-android-surveys-compose

Optional, drop-in Jetpack Compose UI for [PostHog Android](https://github.com/PostHog/posthog-android)
surveys. Opt in by adding this module alongside `com.posthog:posthog-android`
and wiring the delegate once at SDK init:

```kotlin
val config = PostHogAndroidConfig(apiKey).apply {
    surveys = true
    surveysConfig.surveysDelegate = PostHogSurveysComposeDelegate(applicationContext)
}
PostHogAndroid.setup(applicationContext, config)
```

That's it — surveys created in PostHog with appearance, branching, and
multi-question flows will render in a Material 3 modal bottom sheet on top of
your foreground activity.

## Supported question types

| Type                | Component               | Source of visual styling                              |
| ------------------- | ----------------------- | ----------------------------------------------------- |
| Open text           | `OpenText.kt`           | iOS `OpenTextQuestionView`                            |
| Single choice       | `SingleChoice.kt`       | iOS `SingleChoiceQuestionView` + `MultipleChoiceOptions` |
| Multiple choice     | `MultipleChoice.kt`     | iOS `MultipleChoiceQuestionView` + `MultipleChoiceOptions` |
| Number rating (NPS) | `NumberRating.kt`       | iOS `NumberRating`                                    |
| Emoji rating        | `EmojiRating.kt`        | iOS `EmojiRating` + `Resources.swift` SVG paths       |
| Link                | `LinkQuestion.kt`       | iOS `LinkQuestionView`                                |
| Thank-you screen    | `ConfirmationScreen.kt` | iOS `ConfirmationMessage`                             |

Theming reads from [`PostHogDisplaySurveyAppearance`](../posthog/src/main/java/com/posthog/surveys/PostHogDisplaySurveyAppearance.kt)
— customers configure colors, button text, placeholder, and the thank-you
copy from the PostHog dashboard. Every default value matches the iOS
reference (`SurveyDisplayAppearance.getAppearanceWithDefaults` in
`SurveySheet.swift`).

## Known gaps

The following are tracked as follow-ups in `CHANGELOG.md`:

- Server-driven **branching logic** — the sheet always advances to
  `currentQuestionIndex + 1` until the host SDK reports completion.
- **Event dispatch from the delegate** — `survey shown`, `survey sent`, and
  `survey dismissed` are wired through the host SDK callbacks but the
  delegate does not yet emit them itself; if you swap in a different delegate
  you'll need to fire these events.
- **HTML descriptions** — both question and thank-you descriptions render as
  plain text only. HTML is skipped for parity with iOS.
- **Compose UI tests** — visual verification is via emulator + `@Preview`
  composables only at this stage.
- **Dark-mode polish** — defaults are tuned for light backgrounds.

If you need any of the above before they ship, you can write a custom
`PostHogSurveysDelegate` (see `posthog/src/main/java/com/posthog/surveys/`)
and skip this module entirely.

## Trying it locally

Each question type has a `@Preview` composable in its file — open the
`internal/ui/` package in Android Studio and the previews panel will show
both a default and a themed (pastel + orange) rendering side by side.

## Versioning

Released independently of `posthog-android` itself; see this module's
`CHANGELOG.md` for the release history.

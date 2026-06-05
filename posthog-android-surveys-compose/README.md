# posthog-android-surveys-compose

> ⚠️ **Experimental (alpha).** Ships as `1.0.0-alpha01`; the public surface may
> change between alpha releases and the module is excluded from binary
> compatibility validation. Surveys are disabled by default and must also be
> enabled in your PostHog project settings.

Optional, drop-in Jetpack Compose UI for [PostHog Android](https://github.com/PostHog/posthog-android)
surveys. Add this module alongside `com.posthog:posthog-android` and enable
surveys — the core SDK **auto-discovers** this delegate from the classpath, so
no delegate wiring is required:

```kotlin
// build.gradle.kts
implementation("com.posthog:posthog-android-surveys-compose:<version>")

// app init
val config = PostHogAndroidConfig(apiKey).apply {
    surveys = true
}
PostHogAndroid.setup(applicationContext, config)
```

That's it — surveys created in PostHog with appearance, branching, and
multi-question flows render in a Material 3 modal bottom sheet, in its own
window on top of your foreground activity (so it never interferes with your
app's navigation).

If you'd rather manage the delegate yourself, you can still assign it
explicitly: `config.surveysConfig.surveysDelegate = PostHogSurveysComposeDelegate(applicationContext)`.

## Supported question types

| Type                | Component               | Source of visual styling                              |
| ------------------- | ----------------------- | ----------------------------------------------------- |
| Open text           | `OpenText.kt`           | iOS `OpenTextQuestionView`                            |
| Single choice       | `SingleChoice.kt`       | iOS `SingleChoiceQuestionView` + `MultipleChoiceOptions` |
| Multiple choice     | `MultipleChoice.kt`     | iOS `MultipleChoiceQuestionView` + `MultipleChoiceOptions` |
| Number rating (NPS) | `NumberRating.kt`       | iOS `NumberRating`                                    |
| Emoji rating        | `EmojiRating.kt`        | posthog-js `icons.tsx` SVG paths (web parity)         |
| Link                | `LinkQuestion.kt`       | iOS `LinkQuestionView`                                |
| Thank-you screen    | `ConfirmationScreen.kt` | iOS `ConfirmationMessage`                             |

Theming reads from [`PostHogDisplaySurveyAppearance`](../posthog/src/main/java/com/posthog/surveys/PostHogDisplaySurveyAppearance.kt)
— customers configure colors, button text, placeholder, and the thank-you
copy from the PostHog dashboard. Every default value matches the iOS
reference (`SurveyDisplayAppearance.getAppearanceWithDefaults` in
`SurveySheet.swift`).

## Known gaps

The following are tracked as follow-ups in `CHANGELOG.md`:

- **HTML descriptions** — both question and thank-you descriptions render as
  plain text only. HTML is skipped for parity with iOS.
- **Compose UI tests** — visual verification is via emulator + `@Preview`
  composables only at this stage.
- **Dark-mode polish** — defaults are tuned for light backgrounds.

Server-driven branching, the configured popup delay, and the
`survey shown` / `survey sent` / `survey dismissed` events (fired by the host
SDK from the delegate callbacks) are all supported.

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

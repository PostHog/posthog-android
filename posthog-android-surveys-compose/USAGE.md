# How to use the PostHog Android Surveys Compose UI

Optional, drop-in [Jetpack Compose](https://developer.android.com/jetpack/compose) UI for
[PostHog Android](https://github.com/PostHog/posthog-android) surveys. It renders surveys created
in PostHog — with appearance, branching, and multi-question flows — in a Material 3 modal bottom
sheet, in its own window on top of your foreground activity (so it never interferes with your app's
navigation).

> ⚠️ **Pre-1.0 (`0.x`).** The public surface may change between minor versions until `1.0.0`, and
> the module is excluded from binary compatibility validation. Surveys are disabled by default and
> must also be enabled in your PostHog project settings.

## Setup

Add the dependency alongside `com.posthog:posthog-android` and enable surveys. The SDK
**auto-discovers** this UI delegate from the classpath, so no delegate wiring is required:

```kotlin
// app/build.gradle.kts
implementation("com.posthog:posthog-android-surveys-compose:$latestVersion")
```

```kotlin
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig

val config = PostHogAndroidConfig(apiKey).apply {
    surveys = true
}
PostHogAndroid.setup(applicationContext, config)
```

That's it — matching surveys will render automatically.

If you'd rather manage the delegate yourself, assign it explicitly:

```kotlin
import com.posthog.android.surveys.compose.PostHogSurveysComposeDelegate

config.surveysConfig.surveysDelegate = PostHogSurveysComposeDelegate(applicationContext)
```

## Supported question types

- Open text
- Single choice (with optional "other" open choice)
- Multiple choice (with optional "other" open choice)
- Number rating (NPS 0–10, 1–5, 1–7)
- Emoji rating (3- and 5-face)
- Thumbs up/down
- Link
- Thank-you / confirmation screen

Theming reads from [`PostHogDisplaySurveyAppearance`](../posthog/src/main/java/com/posthog/surveys/PostHogDisplaySurveyAppearance.kt)
— customers configure colors, button text, placeholder, input appearance, and the thank-you copy
from the PostHog dashboard, and the UI applies sensible defaults for anything left unset.

Server-driven branching, the configured popup delay, and the `survey shown` / `survey sent` /
`survey dismissed` events (fired by the host SDK from the delegate callbacks) are all supported.

## Known gaps

- **HTML descriptions** — both question and thank-you descriptions render as plain text only.
- **Compose UI tests** — visual verification is via emulator + `@Preview` composables only at this
  stage.
- **Dark-mode polish** — defaults are tuned for light backgrounds.

If you need any of the above before they ship, you can write a custom `PostHogSurveysDelegate` (see
[`posthog/src/main/java/com/posthog/surveys/`](../posthog/src/main/java/com/posthog/surveys/)) and
skip this module entirely.

## Previewing locally

Each question type has a `@Preview` composable in its file — open the `internal/ui/` package in
Android Studio and the previews panel will show both a default and a themed rendering side by side.

## Architecture

For the rationale behind the separate module, the `ComponentDialog` presentation, appearance
resolution, and the emoji SVG-path approach, see [ARCHITECTURE.md](./ARCHITECTURE.md).

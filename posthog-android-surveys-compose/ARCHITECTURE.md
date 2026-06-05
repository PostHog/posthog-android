# Architecture

This document explains why `posthog-android-surveys-compose` is structured
the way it is, so future contributors can extend it without re-deriving the
trade-offs.

## Why a separate module?

The PostHog SDK does not depend on Jetpack Compose. Adding the Compose UI
directly to `posthog-android` would force every customer — including those
using AppCompat / XML, fragments, or no UI at all — to ship the Compose
runtime (~1.5 MB) and pay its activity startup cost. By extracting the UI
into an optional artifact:

- Customers who don't use Compose can keep `posthog-android` lean.
- Customers who do can opt in with a single line at SDK init.
- The SDK's public delegate interface (`PostHogSurveysDelegate`) stays
  framework-agnostic, leaving room for future XML, Fragment, or custom UIs
  without breaking changes.

## Delegate pattern

`PostHogSurveysDelegate` is the host SDK's extension point for survey UI.
The contract:

```kotlin
fun renderSurvey(
    survey: PostHogDisplaySurvey,
    onSurveyShown: OnPostHogSurveyShown,
    onSurveyResponse: OnPostHogSurveyResponse,
    onSurveyClosed: OnPostHogSurveyClosed,
)

fun cleanupSurveys()
```

The SDK drives lifecycle (when to show, what to display), the delegate
drives presentation (how it renders, when callbacks fire). `PostHogSurveysComposeDelegate`
is one implementation of that contract.

We deliberately did **not** make the delegate a Composable — it's a
pre-existing core-SDK interface that runs from the SDK's background event
loop, so it must work on any thread and outside any Compose hierarchy.

## Presentation: a separate window (`ComponentDialog`)

When `renderSurvey` is called we need to render a `ModalBottomSheet` over
whatever Activity is currently in the foreground, **without** participating in
the host's view hierarchy, focus order, or navigation. On iOS this is a
dedicated `UIWindow`; the Android equivalent of "a separate window" is a
`Dialog`.

1. `ActivityProvider` (registered as an `Application.ActivityLifecycleCallbacks`)
   tracks the foreground Activity. This is the standard Android way to find
   the activity without holding stale references.
2. `PostHogSurveyHost.show(...)` builds a fresh `ComposeView` and hosts it in a
   `ComponentDialog` — a separate window layered above the activity. We use
   `ComponentDialog` (not a raw `Dialog`) because it provides a `LifecycleOwner`,
   `SavedStateRegistryOwner`, and `OnBackPressedDispatcher` — exactly the
   `ViewTree*Owner`s a `ComposeView` needs — so Compose runs even when the host
   activity is plain XML / AppCompat with no Compose of its own. The view uses
   `DisposeOnViewTreeLifecycleDestroyed` so it dies cleanly with the dialog.
3. The dialog window is transparent, full-screen, and undimmed; the
   `ModalBottomSheet` inside draws its own scrim, so the host app stays visible
   behind the sheet. `setCancelable(false)` + `setCanceledOnTouchOutside(false)`
   give X-button-only dismissal, mirroring iOS `interactiveDismissDisabled()`.
4. `cleanup()` / dismissal disposes the composition and dismisses the dialog.

Because the dialog is its own window, the same code path works identically over
XML and Compose hosts, and it never interferes with the host app's back press
or navigation.

`PostHogSurveyHost` also honors the configured popup delay
(`surveyPopupDelaySeconds`): it posts the presentation to the main thread after
the delay and re-resolves the foreground activity at fire time.

## State ownership in `SurveySheet`

`SurveySheet` is the only stateful composable in the module. It owns:

- `currentQuestionIndex` — set on each submit to the next-question index the
  host SDK returns (`PostHogNextSurveyQuestion.questionIndex`), so server-driven
  branching is honored rather than blindly incrementing.
- `showingConfirmation` — flips to `true` when a survey completes *and*
  `displayThankYouMessage` is enabled.

Each question type's per-question state (text input, rating value, choice
selection, open-choice input) is owned by a private `*Dispatch` composable
inside `SurveySheet.kt`, keyed by `question.id` so navigating to a new
question resets state.

The visible question composables (`NumberRating`, `EmojiRating`, `OpenText`,
`SingleChoice`, `MultipleChoice`, `LinkQuestion`, `ConfirmationScreen`) are
all **stateless** — they take a value and an `onChange` callback. This
makes them easy to preview, test, and reuse.

## Appearance resolution

`PostHogDisplaySurveyAppearance` (in the core SDK) is the contract surface;
each field is nullable because customers may leave them blank in the PostHog
UI. `SurveyAppearance.kt::resolve()` translates that nullable surface into a
non-null `ResolvedSurveyAppearance` with iOS-matching defaults:

- Colors flow through `parseSurveyColor` (CSS hex / named colors), with iOS's
  same default fallbacks (`tertiarySystemBackground`, `secondaryLabel`,
  `systemFill`).
- Strings fall back to iOS's default copy ("Submit", "Thank you for your
  feedback!", "Close", "Start typing...").
- `questionTextColor` and `placeholderTextColor` are derived from the
  background and primary text colors — iOS does the same at draw time.

The resolved appearance is provided via `LocalSurveyAppearance` so every
question composable reads it through `localAppearance()` rather than a
parameter chain.

## EmojiRating SVG paths

The 5 face shapes use the same SVG artwork posthog-js ships
(`packages/browser/src/extensions/surveys/icons.tsx`). The verbatim `d` path
strings are embedded in `EmojiRating.kt` and parsed at draw time by a small
SVG-path parser (`parseSvgPath`) into a Compose `Path`. They share the
Material-Symbols viewBox `0 -960 960 960`, so the parser scales by `size / 960`
and shifts the negative-y range into the drawing rect. Keeping the raw path
strings (rather than hand-transcribing them into `moveTo`/`cubicTo` calls)
means the artwork stays trivially verifiable against the web source. We chose
not to use Unicode emoji because OEM emoji rendering varies dramatically across
Android devices and would break visual parity.

## Question-type dispatch

`SurveySheet.kt` has a single `when` over the question's runtime class.
Adding a new question type means:

1. Add a new component file in `internal/ui/` with the same stateless +
   `@Preview` pattern.
2. Add a new `*Dispatch` composable in `SurveySheet.kt` that owns its
   per-question state.
3. Add a branch to `QuestionContent`'s `when`.

There is intentionally no plugin system / registry — the question types are
fixed by the PostHog backend contract, so a `when` is sufficient and easy
to follow.

## What this module is **not** responsible for

- **Event dispatch** (`survey shown` / `survey sent` / `survey dismissed`):
  the host SDK fires these from the lifecycle callbacks our delegate calls.
- **Branching decisions**: the host SDK computes the next question (and survey
  completion); the sheet just navigates to the index it returns.
- **Response submission**: the host SDK takes the `PostHogSurveyResponse`
  and sends it to the PostHog API. We just produce the value.

Keeping the boundary tight here means the Compose UI stays a "view layer"
that any other delegate can replace.

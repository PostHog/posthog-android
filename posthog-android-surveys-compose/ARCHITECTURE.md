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

## ComposeView injection

When `renderSurvey` is called we need to render a `ModalBottomSheet` over
whatever Activity is currently in the foreground. The customer hasn't
necessarily set up Compose anywhere in their app, so we can't reuse an
existing composition. Instead:

1. `ActivityProvider` (registered as an `Application.ActivityLifecycleCallbacks`)
   tracks the foreground Activity. This is the standard Android way to find
   the activity without holding stale references.
2. `PostHogSurveyHost.show(...)` builds a fresh `ComposeView` and adds it as
   a child of `android.R.id.content`. The view is configured with
   `DisposeOnViewTreeLifecycleDestroyed` so it dies cleanly if the host
   Activity finishes.
3. `cleanup()` / dismissal removes the view from its parent and disposes the
   composition.

This is essentially what Material 3's own `ModalBottomSheet` expects when
hosted by Compose — we're just wiring it up from the outside. The
`ModalBottomSheet`'s scrim and animation work without further setup.

## State ownership in `SurveySheet`

`SurveySheet` is the only stateful composable in the module. It owns:

- `currentQuestionIndex` — advances on each submit until the host reports
  completion.
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

The 5 face shapes are direct ports of the iOS `Shape` paths in
`Resources.swift` — same normalized coordinate space, same fill-mode
(`EvenOdd` for the face ring), same vertical translation to map the
`[-1, 0]` Y range into the drawing rect. We chose not to use Unicode
emoji because OEM emoji rendering varies dramatically across Android
devices and would break visual parity with iOS.

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
- **Branching logic**: deferred — we always advance to the next question
  until the SDK reports completion.
- **Response submission**: the host SDK takes the `PostHogSurveyResponse`
  and sends it to the PostHog API. We just produce the value.

Keeping the boundary tight here means the Compose UI stays a "view layer"
that any other delegate can replace.

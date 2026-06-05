---
"posthog-android-surveys-compose": minor
---

Initial `0.x` release of the optional Compose survey UI (`com.posthog:posthog-android-surveys-compose`).
This module is pre-1.0 — its public API may change between minor versions. Add the dependency
alongside `com.posthog:posthog-android` and set `surveys = true`; the core SDK auto-discovers the
delegate from the classpath — no `surveysConfig` wiring required.

- All seven question / screen types: number rating (NPS 0–10, 1–5, 1–7), emoji rating (3- and
  5-face plus thumbs up/down), open text, single choice, multiple choice, link, and the
  thank-you / confirmation screen.
- Presented in its own window (`ComponentDialog` + Material 3 `ModalBottomSheet`) above the
  foreground activity, so it works over both XML and Compose hosts and never interferes with host
  navigation. X-button-only dismissal (swipe-down / touch-outside / back are ignored).
- Multi-question surveys with server-driven branching; honors the configured popup delay
  (`surveyPopupDelaySeconds`).
- Theming from `PostHogDisplaySurveyAppearance` — colors, button/placeholder text, input
  appearance, and thank-you copy, with sensible defaults and a CSS hex / named-color parser.
- Fires `survey shown` / `survey sent` / `survey dismissed` through the host SDK callbacks.

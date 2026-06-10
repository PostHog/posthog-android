---
"posthog-android": minor
---

Surveys now have a ready-made UI on Android. Add the new optional
`com.posthog:posthog-android-surveys-compose` module alongside `posthog-android` and set
`surveys = true` — the SDK auto-discovers the Compose UI and renders surveys with no extra wiring.

The UI is a Material 3 modal bottom sheet presented in its own window above your foreground
activity, so it works over both XML and Jetpack Compose apps and never interferes with your app's
navigation. It covers all survey question types (open text, single / multiple choice, number /
NPS rating, emoji rating, thumbs up/down, link) plus the thank-you screen, multi-question
server-driven branching, the configured popup delay, and theming from your PostHog appearance
settings.

Until now the default delegate only logged; you can still provide your own
`PostHogSurveysDelegate` for a custom UI. The module is pre-1.0 (`0.x`).

---
"posthog": minor
"posthog-android": minor
---

Add survey translations support. Surveys can carry per-language overrides for user-visible strings via a `translations` map keyed by language code. At display time the SDK resolves a language (init override → person property `"language"` → device locale), applies any matching translation onto the display model, and stamps the matched key as `$survey_language` on every survey event when a translation actually took effect.

Configure via `PostHogSurveysConfig.overrideDisplayLanguage`. Matching is case-insensitive with a base-language fallback (e.g. `"pt-BR"` falls back to `"pt"`).

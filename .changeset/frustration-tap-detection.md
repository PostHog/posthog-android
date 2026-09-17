---
"posthog-android": minor
"posthog": minor
---

Add independent, default-off `captureRageClicks` and `captureDeadClicks` Android options for actionable View and semantics-backed Compose taps, without requiring replay. Share mobile touch coordinates, privacy-safe element properties and subtree exclusions with semantic capture. Bound rage history and conservative three-second UI-response observation; never send text or local response digests. Drawing-only Compose responses require explicit exclusion.

Add an optional SDK-internal integration callback for capture-context changes and integration-owned guarded enqueue APIs to prevent delayed detector events crossing identity, consent, navigation, session or close transitions, including changes from `beforeSend`. Ordinary capture behavior is unchanged.

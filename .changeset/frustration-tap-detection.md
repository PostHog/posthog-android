---
"posthog-android": minor
"posthog": minor
---

Add independent, default-off `captureRageClicks` and `captureDeadClicks` Android options for actionable View and semantics-backed Compose taps, without requiring replay. Share mobile touch coordinates, privacy-safe element properties and subtree exclusions with semantic capture. Bound rage history and conservative three-second UI-response observation; never send text or local response digests. Drawing-only Compose responses require explicit exclusion.

Add a public `PostHogIntegration.onChange()` notification and SDK-internal integration-owned guarded enqueue APIs to reject invalidated delayed detector events, including changes from `beforeSend`. Notifications invalidate existing observations without pausing new ones during identity, consent, navigation or session changes. Uninstall independently cancels pending work. Ordinary capture behavior is unchanged. With the current Kotlin JVM-default configuration, Java implementations of `PostHogIntegration` must implement the new `onChange()` method when recompiling.

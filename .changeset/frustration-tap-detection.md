---
"posthog-android": minor
"posthog": minor
---

Add independent, default-off `captureRageClicks` and `captureDeadClicks` Android options for actionable View and semantics-backed Compose taps, without requiring replay. Share mobile touch coordinates, privacy-safe element properties and subtree exclusions with semantic capture. Bound rage history and conservative three-second UI-response observation; never send text or local response digests. Drawing-only Compose responses require explicit exclusion.

Add a public `PostHogIntegration.onChange()` notification so the Android integration can invalidate pending detector observations. Ready detector events use ordinary capture without requiring a session ID. The integration checks consent and invalidation before capture; context changes during `beforeSend` or enqueue follow ordinary capture behavior. Uninstall independently cancels pending work. With the current Kotlin JVM-default configuration, Java implementations of `PostHogIntegration` must implement the new `onChange()` method when recompiling.

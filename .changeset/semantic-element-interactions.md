---
"posthog-android": minor
---

Add opt-in `captureElementInteractions` for actionable View and Compose taps, independent of session replay. Emits privacy-safe `$autocapture` events with browser-compatible element properties (types, resource IDs/test tags and hierarchy positions), the mobile `touch` event type and window-local touch coordinates in dp, never text or input values. Add keyed View and Compose subtree exclusions and respect explicit replay masking.

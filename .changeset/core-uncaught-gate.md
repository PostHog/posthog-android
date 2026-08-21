---
'posthog': patch
---

`PostHogErrorTrackingAutoCaptureIntegration` can now be gated on a caller-supplied strategy instead of the built-in gate (local `errorTrackingConfig.autoCapture` with remote config as a kill-switch): a new `PostHogErrorTrackingAutoCaptureIntegration(config, enabledGate)` constructor lets SDK layers that never fetch remote config (e.g. the server SDK) decide autocapture purely from local config. The uncaught handler also delivers captures through an internal `CaptureTarget` seam (`installWith`) so it can drive clients that are not a core `PostHogInterface`, and when no previous default handler exists it now reproduces the JVM's own `Exception in thread ...` stderr output, so installing capture never hides a crash from log collection. Android behavior and the existing `install(PostHogInterface)` path are otherwise unchanged; the additions are internal (`@PostHogInternal`) and visible only because of the multi-module architecture.

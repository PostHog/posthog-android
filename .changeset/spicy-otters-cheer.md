---
"posthog": minor
"posthog-android": minor
---

Add `addExceptionStep(message, properties?)` and the `errorTrackingConfig.exceptionSteps` config (enabled by default, 32 KiB byte budget). Recorded steps are buffered in a rolling, byte-bounded FIFO and attached to every captured `$exception` event as `$exception_steps`.

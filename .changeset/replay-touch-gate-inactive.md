---
"posthog-android": patch
---

Session Replay: skip per-touch capture work when replay is not actively recording. The touch interceptor previously copied the `MotionEvent` and submitted a task to the replay executor on every touch, gating on the recording state only inside the submitted task. For sessions that are sampled out (the common case) this ran on the main thread and contended on the single replay executor's work-queue lock, which could stall the UI under load. The recording state is now checked before any allocation or executor submission.

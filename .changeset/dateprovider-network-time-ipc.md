---
"posthog-android": patch
---

Stop querying network time on every timestamp. `PostHogAndroidDateProvider.currentTimeMillis()` called `SystemClock.currentNetworkTimeClock().millis()` on each invocation, which performs a Binder IPC to a system service — so hot paths like the session-replay touch interceptor did an IPC on the main thread for every touch and could ANR under load. Network time is now sampled at most once per minute and intermediate timestamps are derived from the monotonic `elapsedRealtime` delta, preserving network-time correction without the per-call IPC.

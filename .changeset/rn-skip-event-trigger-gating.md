---
"posthog": patch
"posthog-android": patch
---

Session replay: skip native event-trigger gating when running under React Native (`sdkName == "posthog-react-native"`). `PostHogRemoteConfig.getEventTriggers()` returns null for RN, so the native recorder no longer self-gates and `startSessionReplay` records as instructed. React Native evaluates `sessionRecording.eventTriggers` in its JS layer and drives recording explicitly; the native gate could never be satisfied because JS-captured events never reach the native capture pipeline, so event-triggered replay never recorded on RN. The linked-flag and sampling gates are unchanged, and non-RN behavior is unaffected.

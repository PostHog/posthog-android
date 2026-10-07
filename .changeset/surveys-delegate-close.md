---
"posthog-android": patch
---

Require a custom `PostHogSurveysDelegate` that can't show a survey to call `onSurveyClosed`, or later surveys won't render

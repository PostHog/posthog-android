---
"posthog": minor
"posthog-android": minor
"posthog-android-surveys-compose": minor
---

Support survey partial response collection. When enabled, submit cumulative answers after each question with a stable submission ID and completion status, matching posthog-js.

Persist unfinished survey progress across app restarts and restore the submission ID, collected answers, and next question. Clear progress on completion, dismissal, SDK reset, and incompatible survey updates. The Compose renderer starts at the restored question. Custom delegates opt in through `PostHogSurveysResumeAwareDelegate`; other delegates start fresh.

Keep unfinished surveys across Activity teardown, preserve unreadable progress during Direct Boot, and invalidate delayed responses on reset without mixing user identities.

Use the existing delegate cleanup callback on reset, including custom renderers. Discard visible, delayed, and retained Compose survey input while preserving fresh presentations when a delegate is reused. Reset generations remain internal to the SDK.

Preserve unfinished progress when startup has no cached survey configuration; confirmed empty survey lists still clear removed surveys.

---
"posthog": minor
"posthog-android": minor
"posthog-android-surveys-compose": minor
---

Support survey partial response collection. When enabled, submit cumulative answers after each question with a stable submission ID and completion status, matching posthog-js.

Persist unfinished survey progress across app restarts and restore the submission ID, collected answers, and next question. Clear progress on completion, dismissal, SDK reset, and incompatible survey updates. The Compose renderer starts at the restored question.

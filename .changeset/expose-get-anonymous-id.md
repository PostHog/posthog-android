---
"posthog": minor
"posthog-android": minor
---

Expose `getAnonymousId()` on `PostHogInterface`. Returns the anonymous ID generated before any `identify()` call — unlike `distinctId()`, this does not change after identification.

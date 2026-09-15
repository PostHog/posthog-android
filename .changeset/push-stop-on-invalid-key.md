---
"posthog": patch
"posthog-android": patch
---

fix: stop retrying push registration when the project API key is not valid

A device that gets `401 invalid_api_key` from the registration endpoint stops sending push
registrations for that key instead of re-posting on every app open. Adds the internal
`PostHogPreferences.PUSH_SUBSCRIPTION_REJECTED` key, which holds that verdict and survives `reset()`.

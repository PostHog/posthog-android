---
"posthog-android": minor
"posthog": minor
---

Add optional identity verification for push subscriptions: set `pushIdentityProvider` on the config to attach a backend-minted `identity_token` (JWT) to push register/unregister requests. The token is cached per identity, reused across in-session retries, and refreshed once automatically when the backend rejects a request with 401.

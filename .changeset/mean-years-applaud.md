---
"posthog-server": minor
---

Add the ability to integrate custom caching for feature flag definitions in the server SDK.

This introduces an async-capable `PostHogFlagDefinitionCacheProvider` public API and a `PostHogBlockingFlagDefinitionCacheProvider` base class for synchronous cache backends.

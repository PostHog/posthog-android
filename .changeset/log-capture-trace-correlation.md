---
"posthog": minor
"posthog-android": minor
---

Add a public `captureLog()` API for capturing logs with optional W3C trace correlation (`traceId`/`spanId`/`traceFlags`), matching iOS, web, and React Native. The `logger` facade is unchanged.

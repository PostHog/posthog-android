---
"posthog-server": minor
---

Add an optional OpenTelemetry span processor and request-scope bridge for session and person context on application spans. The processor is bound to one PostHog client. Only scopes owned by that client add IDs, and a disabled or opted-out client adds none. A nested scope without a client keeps the outer scope's owner. If the application replaces its client, build a new processor.

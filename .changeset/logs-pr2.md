---
"posthog": minor
"posthog-android": minor
---

Internal plumbing for the logs feature: log record + severity types, OTLP/JSON
builder, `/i/v1/logs` endpoint on `PostHogApi`, and a `PostHogQueue` wired
through `PostHog.setup`. No public capture API.

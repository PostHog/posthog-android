---
"posthog": minor
"posthog-android": minor
---

Add public Logs API: `PostHog.logger.trace/debug/info/warn/error/fatal(message, attrs?)` plus a `PostHogLogsConfig` for serviceName, environment, resourceAttributes, rate cap, and `addBeforeSend` redaction hooks. Logs ship via OTLP/JSON to `/i/v1/logs` and pick up auto-attached attributes (`app.state`, distinctId, sessionId, screen name, feature flags). Matches the equivalent surfaces on posthog-ios and posthog-react-native.

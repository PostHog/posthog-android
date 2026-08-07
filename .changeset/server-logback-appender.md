---
'posthog-server-logback': minor
---

Initial `0.x` release of `com.posthog:posthog-server-logback` — a Logback appender that reports logged errors to PostHog Error Tracking through the server SDK. This module is pre-1.0; its public API may change between minor versions.

- `PostHogAppender` (`ch.qos.logback.core.AppenderBase<ILoggingEvent>`, package `com.posthog.server.logback`) captures events at or above `minimumCaptureLevel` (default `ERROR`) that carry a `Throwable`, sending them through the server SDK's `captureException` so request-context distinct-id resolution and in-app frame config apply automatically.
- Register a configured server client once at startup with `PostHogAppender.setPostHog(client)`; events logged before registration are dropped. Alternatively self-configure from `logback.xml` via `<apiKey>`/`<host>` (each self-configuring appender owns its client and closes it on `stop()`, so a Logback config reload keeps capturing). An application-registered client always wins.
- Events from `com.posthog…` loggers are always skipped (recursion guard), events without a throwable are skipped (no message-only synthesis in v1), and captured events carry `$exception_level` (`error`, or `warning` when the threshold is lowered), `logger_name`, and the log message under `log_message` when it differs from the throwable message.
- Depends on `com.posthog:posthog-server` (transitive `api`); Logback is `compileOnly` — provide `logback-classic` yourself (1.3.x line for Java 8 compatibility).

---
'posthog-server': minor
---

Opt in to capturing uncaught JVM exceptions on the server SDK via `PostHogConfig.captureUncaughtExceptions` (also on the config `Builder`). When enabled, `PostHog` installs a global `Thread.defaultUncaughtExceptionHandler` on setup that captures the crashing exception as a fatal, unhandled `$exception` event (mechanism `UncaughtExceptionHandler`), flushes, and then delegates to the previously registered handler; the handler is removed again on `close()`. Unlike the Android SDK this is gated purely on the local flag — the server SDK never fetches remote config. A fatal `$exception` event is enqueued and sent synchronously on the crashing thread, bypassing `flushAt`, so capturing the crash does not depend on the periodic flush; delivery is still best-effort under an immediate hard exit.

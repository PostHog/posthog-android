---
'posthog-server': minor
---

Add `captureUncaughtExceptions`: opt in to capturing uncaught JVM exceptions as error tracking events, with a best-effort flush before the process exits. The SDK's flush timer is now a daemon thread and no longer keeps a finished JVM alive until `close()`.

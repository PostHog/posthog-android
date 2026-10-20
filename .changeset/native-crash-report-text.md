---
"posthog-android": minor
---

Add `$exception_crash_report` to native crash `$exception` events with the tombstone text as debuggerd prints it: the process header, signal, the crashing thread's registers, and every thread's backtrace

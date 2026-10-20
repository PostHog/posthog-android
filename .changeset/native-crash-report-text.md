---
"posthog-android": minor
"posthog": patch
---

Add `$exception_crash_report` to native crash `$exception` events with the tombstone text as debuggerd prints it: the process header, signal, the crashing thread's registers, and every thread's backtrace. The report is left out when it would push the event past the capture size limit

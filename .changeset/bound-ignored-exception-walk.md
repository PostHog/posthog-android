---
"posthog": patch
---

Bound the `ignoredExceptionTypes` prefilter's cause-chain walk. It previously walked `throwable.cause` with no depth limit and equality-based cycle detection, so a throwable whose `getCause()` returns a fresh object on every call looped forever in the prefilter, and one whose `equals`/`hashCode` misreport identity could stop the walk early and miss an ignored cause. The walk is now identity-based and capped at 50 links; hitting the cap without a match lets the capture proceed rather than dropping the event.

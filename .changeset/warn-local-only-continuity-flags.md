---
"posthog-server": patch
---

Log a message when `evaluateFlags(onlyEvaluateLocally = true)` omits flags because experience continuity is on. Local evaluation cannot resolve these flags, so a local-only snapshot does not contain them. The message names the flags. Like all SDK logs, it shows only when `debug` is on.

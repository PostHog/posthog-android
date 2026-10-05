---
"posthog-server": patch
---

fix(flags): interpret date-only and naive date conditions, and relative date lookbacks, in UTC during local evaluation instead of the JVM default timezone

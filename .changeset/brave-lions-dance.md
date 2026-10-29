---
"posthog-android": minor
---

Session replay screenshots now default to one pixel per dp (about 1/9 of the pixels on a 3x screen, so smaller uploads), using a filtered downscale that keeps small text readable. Set `screenshotScale = 1f` to keep native resolution.

**Breaking change to the experimental `screenshotScale`:** its type changes from `Float` to `Float?`, and `null` (the new default) means one pixel per dp. Explicit values keep their meaning, and assigning one (`screenshotScale = 0.5f`) still compiles. Code compiled against 3.63.x must be rebuilt. Kotlin code that reads it into a `Float` no longer compiles, and Java code that reads it into a `float` throws `NullPointerException` while it is unset.

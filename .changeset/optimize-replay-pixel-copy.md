---
'posthog-android': minor
---

Add an experimental `sessionReplayConfig.optimizeScreenshots` option to reduce screenshot overhead with a reusable bitmap at half the width and height. It defaults to `false`, preserving full-resolution ARGB_8888 capture. When enabled, RGB_565 reduces image detail and removes alpha, making transparent window regions appear black; captures are skipped while a timed-out PixelCopy still owns the reusable bitmap.

---
'posthog-android': minor
---

Add experimental `sessionReplayConfig.screenshotScale`, `screenshotCompressionQuality`, and `screenshotColorMode` options. Scale is clamped to 0.1–1.0 and WebP quality to 0–100; defaults remain full resolution, quality 30, and ARGB_8888. RGB_565 can reduce bitmap memory at the cost of color precision and alpha. Screenshot destinations are reused when available, and idle destinations are released when recording stops; pending PixelCopy destinations are never reused before completion.

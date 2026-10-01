---
"posthog": patch
---

Compress request bodies with `java.util.zip.GZIPOutputStream` instead of Okio `GzipSink`, so apps that resolve an Okio version older than 3.11.0 no longer send corrupt gzip bodies

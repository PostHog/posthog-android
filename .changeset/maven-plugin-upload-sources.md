---
"posthog-maven-plugin": minor
---

Add `posthog-maven-plugin` with an `upload-sources` goal. It runs `posthog-cli jvm upload` to upload the project's sources and writes the id they were uploaded under into `posthog-meta.properties`, so error tracking shows source code on the frames of exceptions `posthog-server` captures. When `posthog-cli` is missing or too old, or PostHog credentials are missing, the goal skips the upload with a warning. Set `requireUpload` to fail the build instead.

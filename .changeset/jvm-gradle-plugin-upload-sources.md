---
"posthog-android-gradle-plugin": minor
---

Add the `com.posthog.jvm` plugin for server JVM applications. Its `uploadPostHogJvmSources` task runs `posthog-cli jvm upload` to upload the `main` sources and writes the id they were uploaded under into `posthog-meta.properties` in the `main` resources, so error tracking shows source code on the frames of exceptions `posthog-server` captures. The upload also includes the sources that annotation processors generate, and uploads UTF-8 copies of Java sources that `compileJava` reads in another encoding. When `posthog-cli` is missing or too old, or PostHog credentials are missing, the task skips the upload with a warning. Set `posthog { requireUpload = true }` to fail the build instead.

---
'posthog-android-gradle-plugin': minor
---

Read PostHog CLI credentials from a dotenv file via the `posthog.dotenvFile` gradle property. Relative paths resolve against the root project, and the file reaches `posthog-cli` (>= 0.8.4) as `POSTHOG_CLI_DOTENV_FILE` on the upload tasks — no more exporting `POSTHOG_CLI_*` into the Gradle daemon's environment. Process env still wins inside the CLI, and a missing file is a warning there, not a build failure. Also settable per task via the new `postHogDotenvFile` property.

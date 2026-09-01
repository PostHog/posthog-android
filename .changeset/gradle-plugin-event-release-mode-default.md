---
'posthog-android-gradle-plugin': minor
---

Default `posthog.releaseMode` to `event`. The proguard mapping now uploads release-independent. Each event resolves its own release from the `$app_namespace` / `$app_version` / `$app_build` the SDK already sends. Two releases that ship the same mapping no longer both report whichever release uploaded it first.

Set `posthog.releaseMode=symbol-set`, or `POSTHOG_RELEASE_MODE=symbol-set`, to keep stamping the release onto the uploaded mapping.

Event mode needs posthog-cli 0.13.0 or newer. That version added `--release-mode` to `proguard upload`. The release coordinates the build sends must match the app's applicationId, versionName and versionCode.

---
'posthog-android-gradle-plugin': minor
---

Default `posthog.releaseMode` to `event`. The proguard mapping is now uploaded release-independent, and each event resolves its own release from the `$app_namespace` / `$app_version` / `$app_build` the SDK already sends, so two releases that ship the same mapping no longer both report whichever release uploaded it first. Set `posthog.releaseMode=symbol-set`, or `POSTHOG_RELEASE_MODE=symbol-set`, to keep stamping the release onto the uploaded mapping. Event mode needs posthog-cli >= 0.12.0, and the release coordinates the build sends have to match the app's applicationId, versionName and versionCode.

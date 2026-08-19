---
'posthog-android-gradle-plugin': minor
---

Add the `posthog.releaseMode` gradle property (falling back to the `POSTHOG_RELEASE_MODE` environment variable), which selects how the uploaded proguard mapping is associated with a release. `symbol-set`, the default, is unchanged. Experimental `event` passes `--release-mode event` to `posthog-cli` (>= 0.12.0) so the mapping is uploaded release-independent, and each event resolves its own release from the `$app_namespace` / `$app_version` / `$app_build` the SDK already sends — two releases that ship the same mapping no longer both report whichever release uploaded it first. An unrecognized value fails the build instead of silently falling back.

---
'posthog-android-gradle-plugin': minor
---

Deprecate the `posthog.releaseMode` gradle property and the upload task's `releaseMode` input. Both are ignored now, with a build-log warning: the plugin uploads the proguard mapping bound to the release it creates, which is what it did before the property existed. Remove the property to silence the warning. The `POSTHOG_RELEASE_MODE` environment variable no longer affects this upload either: the task warns when an inherited value used to select event mode, and pins `symbol-set` into the posthog-cli environment so the value cannot leave the mapping release-independent on an older CLI.

Event mode only helps when two releases ship a byte-identical mapping. The map id is already a content hash, so an ordinary release that changes code gets its own symbol set and never collides with an earlier one.

---
"posthog-android-gradle-plugin": patch
---

On Windows, run the `posthog-cli.exe` that the npm package installs, instead of its `posthog-cli.cmd` launcher, so that arguments no longer pass through `cmd`. When only the launcher can run, the upload tasks refuse an argument that `cmd` would change, such as a path or a release version with `&` or `%` in it, instead of running it.

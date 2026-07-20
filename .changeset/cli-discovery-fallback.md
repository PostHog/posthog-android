---
'posthog-android-gradle-plugin': minor
---

Locate posthog-cli in well-known install locations (nvm, npm global, homebrew, cargo) when it is not on the build's PATH — IDE-launched Gradle daemons don't source shell profiles, which made uploads fail from Android Studio. Mirrors the lookup in posthog-ios `upload-symbols.sh`. An explicitly configured `postHogExecutable` is still used verbatim.

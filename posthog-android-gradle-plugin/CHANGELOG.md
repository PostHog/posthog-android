## Next

## 1.4.0

### Minor Changes

- 3ef3756: Read PostHog CLI credentials from a dotenv file via the `posthog.dotenvFile` gradle property. Relative paths resolve against the root project, and the file reaches `posthog-cli` (>= 0.8.4) as `POSTHOG_CLI_DOTENV_FILE` on the upload tasks — no more exporting `POSTHOG_CLI_*` into the Gradle daemon's environment. Process env still wins inside the CLI, and a missing file is a warning there, not a build failure. Also settable per task via the new `postHogDotenvFile` property.

## 1.3.0

### Minor Changes

- 9e114ba: Locate posthog-cli in well-known install locations (nvm, npm global, homebrew, cargo) when it is not on the build's PATH — IDE-launched Gradle daemons don't source shell profiles, which made uploads fail from Android Studio. Mirrors the lookup in posthog-ios `upload-symbols.sh`. An explicitly configured `postHogExecutable` is still used verbatim.

## 1.2.0

### Minor Changes

- ebef906: Attach release info (`applicationId`, `versionName`, `versionCode`) to proguard mapping uploads via the new posthog-cli `--release-name`, `--release-version`, and `--build` flags.

## 1.1.0

### Minor Changes

- 1144733: Configurable executable and env for CLI task

## 1.0.4

### Patch Changes

- a5c3a78: fix duplicate map ids

## 1.0.3

### Patch Changes

- 57efb8a: test new release process

## 1.0.2 - 2025-12-03

- revert: plugin marker group id ([#339](https://github.com/PostHog/posthog-android/pull/339)).

## 1.0.1 - 2025-12-02

- fix: plugin marker group id ([#339](https://github.com/PostHog/posthog-android/pull/339)).

## 1.0.0 - 2025-12-02

- feat: proguard support ([#316](https://github.com/PostHog/posthog-android/pull/316)).

```kotlin
plugins {
    id("com.posthog.android") version "$version"
    ...
}
```

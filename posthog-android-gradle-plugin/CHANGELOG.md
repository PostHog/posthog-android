## Next

## 1.6.0

### Minor Changes

- 79c9201: Deprecate the `posthog.releaseMode` gradle property and the upload task's `releaseMode` input. Both are ignored now, with a build-log warning: the plugin uploads the proguard mapping bound to the release it creates, which is what it did before the property existed. Remove the property to silence the warning. The `POSTHOG_RELEASE_MODE` environment variable no longer affects this upload either: the task warns when an inherited value used to select event mode, and pins `symbol-set` into the posthog-cli environment so the value cannot leave the mapping release-independent on an older CLI.
  
  Event mode only helps when two releases ship a byte-identical mapping. The map id is already a content hash, so an ordinary release that changes code gets its own symbol set and never collides with an earlier one.

## 1.5.2

### Patch Changes

- e5ffc0b: Stop publishing `org.jetbrains.kotlin:kotlin-gradle-plugin`, so your build compiles with the Kotlin version it declares — a build that relied on this plugin to supply it must now declare it itself.

## 1.5.1

### Patch Changes

- faf72aa: Resolve project-local `@posthog/cli` installations on macOS and Linux for symbol and ProGuard mapping uploads.

## 1.5.0

### Minor Changes

- 89fcfd0: Add the `posthog.releaseMode` gradle property (falling back to the `POSTHOG_RELEASE_MODE` environment variable), which selects how the uploaded proguard mapping is associated with a release. `symbol-set`, the default, is unchanged. Experimental `event` passes `--release-mode event` to `posthog-cli` (>= 0.12.0) so the mapping is uploaded release-independent, and each event resolves its own release from the `$app_namespace` / `$app_version` / `$app_build` the SDK already sends — two releases that ship the same mapping no longer both report whichever release uploaded it first. An unrecognized value fails the build instead of silently falling back.
- d67097f: Upload native (`.so`) debug symbols via `posthog-cli symbol-sets upload` (>= 0.7.32), so native crash stack traces can be symbolicated. Opt in with the new plugin extension:

  ```kotlin
  posthog {
      uploadNativeSymbols.set(true)
  }
  ```

  When enabled, a new `uploadPostHogNativeSymbols<Variant>` task reads the variant's unstripped merged native libs (NDK builds, `jniLibs`, and libraries packaged by dependencies) and runs automatically after `assemble`/`install`/`bundle` for non-debuggable variants, minified or not. Set `includeNativeSymbolSources.set(true)` to also bundle the project sources referenced by the debug info (off by default). The task can always be invoked explicitly, for any variant, without opting in.

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

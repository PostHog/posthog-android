# PostHog Gradle plugins

This build publishes two Gradle plugins in one artifact, `com.posthog:posthog-android-gradle-plugin`:

- `com.posthog.android`, for Android apps. See the [Android docs](https://posthog.com/docs/libraries/android).
- `com.posthog.jvm`, for server JVM applications, described below.

## com.posthog.jvm

Uploads your application's Java and Kotlin sources to PostHog, so error tracking shows the source code around each frame of the exceptions your app captures.

The `uploadPostHogJvmSources` task runs [`posthog-cli`](https://github.com/PostHog/posthog/tree/master/cli) `jvm upload`. The CLI bundles the sources, uploads them under an id derived from their content and the release, and writes that id into `posthog-meta.properties`. The plugin adds the file to the `main` resources, so every archive and image of the application carries it: `jar`, `bootJar`, `shadowJar`, `war`, Jib, and the application distribution. At startup, [`posthog-server`](../posthog-server) reads the file and sends the id on every exception frame, and PostHog looks up the uploaded sources by that id.

A build of unchanged code in the same release reuses the existing upload and sends no source files. Changed code, or a new release, gets a new id and a new upload. Because the file is written on every build, it always matches the code that was compiled.

### Requirements

- [`posthog-cli`](https://github.com/PostHog/posthog/tree/master/cli), installed with `npm install -g @posthog/cli`, `brew install posthog/tap/posthog-cli`, or the install script.
- `posthog-server` with `posthog-meta.properties` support, and no `releaseIdentifier` set in code (an explicit value wins over the file).
- A personal API key with error tracking write access, for the upload.
- Gradle 8.12 or newer, with the `java` plugin or a plugin that applies it, such as `org.jetbrains.kotlin.jvm` or `org.springframework.boot`.

### Usage

The plugin is published to Maven Central, so list it in the plugin repositories:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
```

Then apply it in the module that builds the application, not in the libraries it uses:

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "2.1.21"
    id("com.posthog.jvm") version "1.9.0"
}
```

The upload task runs before `processResources`, so `build`, `jar`, `bootJar`, `test` and `run` all upload when the sources changed.

### Releases

Each upload belongs to a release, and exceptions are attributed to it. The release name defaults to the project name, and the version defaults to the current git commit: `posthog-cli` reads it from the repository, or from the CI environment on GitHub Actions. So every deployed commit is its own release. A build with no git commit, such as a Docker build that copies only the sources, uploads without a release unless you set `releaseVersion`. Its exceptions still show source code.

### Credentials

`posthog-cli` reads them from the environment, from a dotenv file, or from `posthog-cli login`:

- `POSTHOG_CLI_HOST`: your PostHog app host, for example `https://us.posthog.com`
- `POSTHOG_CLI_PROJECT_ID`: your project id
- `POSTHOG_CLI_API_KEY`: a personal API key with error tracking write access

To use a dotenv file, set the Gradle property `posthog.dotenvFile`, for example `posthog.dotenvFile=.env` in `gradle.properties`. A relative path resolves against the root project.

### Builds without credentials

When `posthog-cli` is missing or too old to have `jvm upload`, or the credentials are missing, the task skips the upload with a warning and the build continues. Developers can build without setting up PostHog. A skipped build ships no id, so its exceptions show no source code. It also deletes the `posthog-meta.properties` an earlier build left behind, so the app never reports an id that belongs to other code.

In CI, set `requireUpload` or pass `-Pposthog.requireUpload=true`. Then a missing or outdated CLI, or a missing token, fails the build instead of shipping a build without source code. A failed upload fails the build either way.

To turn the upload off, set `skip` or pass `-Pposthog.skip=true`. Don't exclude the task with `-x`: an excluded task can't delete the file an earlier build left behind.

### Options

```kotlin
posthog {
    requireUpload = true
}
```

| Option | Gradle property | Default | Description |
|---|---|---|---|
| `skip` | `posthog.skip` | `false` | Skip the upload. |
| `requireUpload` | `posthog.requireUpload` | `false` | Fail the build when `posthog-cli` is missing or too old, or the credentials are missing, instead of skipping the upload. |
| `releaseName` | none | the project name | The release the upload belongs to. |
| `releaseVersion` | `posthog.releaseVersion` | the git commit | The version of that release. Set it to `project.version.toString()` if you bump the project version on every release. |
| `cliExecutable` | `posthog.cliExecutable` | found automatically | Path to `posthog-cli`, relative to the project. Without it, the plugin checks `PATH`, then the common install locations. |
| `additionalSourceDirs` | none | none | More source directories to upload with the `main` source set. |

### What it doesn't cover yet

- The SDK sends one id, the one in the application's `posthog-meta.properties`. Frames from other modules show source code only when their sources are in that upload, so in a multi-module build, add the other modules' source directories to `additionalSourceDirs`. Frames from a library built somewhere else show no source code.
- Java and Kotlin files only. Groovy and Scala sources are not uploaded.
- GraalVM native images don't include `posthog-meta.properties` unless you register it as a resource.

# posthog-maven-plugin

Uploads your application's Java and Kotlin sources to PostHog, so error tracking shows the source code around each frame of the exceptions your app captures.

On every build, the `upload-sources` goal runs [`posthog-cli`](https://github.com/PostHog/posthog/tree/master/cli) `jvm upload`. The CLI bundles the sources, uploads them under an id derived from their content and the release, and writes that id into `posthog-meta.properties` in a generated resources directory. The file is packaged with your classes. At startup, [`posthog-server`](../posthog-server) reads it and sends the id on every exception frame, and PostHog looks up the uploaded sources by that id.

A build of unchanged code in the same release reuses the existing upload and sends no source files. Changed code, or a new release, gets a new id and a new upload, so exceptions are attributed to the release that threw them. Because the file is written on every build, it always matches the code that was compiled.

## Requirements

- [`posthog-cli`](https://github.com/PostHog/posthog/tree/master/cli), installed with `npm install -g @posthog/cli`, `brew install posthog/tap/posthog-cli`, or the install script.
- `posthog-server` with `posthog-meta.properties` support, and no `releaseIdentifier` set in code (an explicit value wins over the file).
- A personal API key with error tracking write access, for the upload.

## Usage

```xml
<build>
  <plugins>
    <plugin>
      <groupId>com.posthog</groupId>
      <artifactId>posthog-maven-plugin</artifactId>
      <version>0.1.0</version>
      <executions>
        <execution>
          <goals>
            <goal>upload-sources</goal>
          </goals>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

The goal binds to the `generate-resources` phase, so `mvn package` uploads before it packages.

### Releases

Each upload belongs to a release, and exceptions are attributed to it. The release name defaults to the artifact id, and the version defaults to the current git commit: `posthog-cli` reads it from the repository, or from the CI environment on GitHub Actions. So every deployed commit is its own release, even when the project version stays at `0.0.1-SNAPSHOT`. A build with no git commit, such as a Docker build that copies only the sources, uploads without a release unless you set `releaseVersion`. Its exceptions still show source code.

### Credentials

`posthog-cli` reads them from the environment, from a dotenv file, or from `posthog-cli login`:

- `POSTHOG_CLI_HOST`: your PostHog app host, for example `https://us.posthog.com`
- `POSTHOG_CLI_PROJECT_ID`: your project id
- `POSTHOG_CLI_API_KEY`: a personal API key with error tracking write access

To use a dotenv file, set `<dotenvFile>.env</dotenvFile>` or pass `-Dposthog.dotenvFile=.env`.

### Builds without credentials

When `posthog-cli` is missing or too old to have `jvm upload`, or the credentials are missing, the plugin skips the upload with a warning and the build continues. Developers can build without setting up PostHog. A skipped build ships no id, so its exceptions show no source code. It also deletes the `posthog-meta.properties` an earlier build left behind, so the app never reports an id that belongs to other code.

In CI, set `<requireUpload>true</requireUpload>` or pass `-Dposthog.requireUpload=true`. Then a missing or outdated CLI, or a missing token, fails the build instead of shipping a build without source code. A failed upload fails the build either way.

A developer who has run `posthog-cli login` uploads from local builds too. Pass `-Dposthog.skip=true` to turn the upload off.

## Parameters

| Parameter | User property | Default | Description |
|---|---|---|---|
| `skip` | `posthog.skip` | `false` | Skip the upload. |
| `requireUpload` | `posthog.requireUpload` | `false` | Fail the build when `posthog-cli` is missing or too old, or the credentials are missing, instead of skipping the upload. |
| `cliExecutable` | `posthog.cliExecutable` | found automatically | Path to `posthog-cli`. Without it, the plugin checks `~/.posthog/posthog-cli`, then `PATH`, then Homebrew, `~/.cargo/bin` and `~/.local/bin`. |
| `dotenvFile` | `posthog.dotenvFile` | none | A dotenv file `posthog-cli` reads credentials from. |
| `releaseName` | `posthog.releaseName` | `${project.artifactId}` | The release the upload is associated with. |
| `releaseVersion` | `posthog.releaseVersion` | the git commit | The version of that release. Set it to `${project.version}` if you bump the project version on every release. |
| `outputDirectory` | `posthog.outputDirectory` | `${project.build.directory}/generated-resources/posthog` | Where `posthog-meta.properties` is written. The plugin adds it as a resource directory. |
| `sourceRoots` | none | the project's compile source roots, plus the `<sourceDirs>` of `kotlin-maven-plugin` | The directories to upload. Setting it replaces the default. |

## What it doesn't cover yet

- The SDK sends one id, the one in the application's `posthog-meta.properties`. Frames from other modules show source code only when their sources are in that upload, so in a multi-module build, run the goal in the application module and list the other modules' source directories in `sourceRoots`. Frames from a library built somewhere else show no source code.
- GraalVM native images don't include `posthog-meta.properties` unless you register it as a resource.

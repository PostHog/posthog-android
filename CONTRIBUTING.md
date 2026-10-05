# Contributing

If you would like to contribute code to `posthog-android` you can do so through GitHub by forking the repository and opening a pull request against `main`.

## Development commands

Prefer the [Makefile](./Makefile) wrappers:

| Task | Command |
|---|---|
| Build, including the Gradle plugin | `make compile` |
| Run JVM and Android tests, including Compose and the Gradle plugin | `make test` |
| Run core (JVM) tests only | `make testJava` |
| Run a specific core test class | `./gradlew :posthog:test --tests "com.posthog.PostHogTest"` |
| Run Compose survey interaction tests | `make testSurveyUI` |
| Check formatting | `make checkFormat` |
| Fix formatting | `make format` |
| Clean | `make clean` |
| Dump API surface | `make api` |
| Test coverage report | `make testReport` |
| Dry release (local Maven) | `make dryRelease` |
| Stop Gradle daemons | `make stop` |

Use focused tests during iteration and select module/behavior checks before submitting SDK changes. Core JVM tests do not cover Android or Compose behavior; use the relevant Gradle module/task for Android or server tests, and `make testSurveyUI` for Compose survey interactions (it enables debug tests). Formatting uses Spotless + ktlint; review any diff from `make format`. For Markdown-only edits, check links and `git diff --check` rather than running SDK builds/tests.

## Public API changes

Public API is hard to change once it ships, so agree on it before writing the implementation. Our [SDK guidelines](https://posthog.com/handbook/engineering/sdks/guidelines) explain how we design it.

This section is for external contributors. PostHog maintainers (members of the PostHog GitHub org) agree on API shape in the PR itself, so they don't need a separate issue.

- **Before you start:** if you need something the SDK doesn't support and it would add or change a public option, method, or type, open an issue describing your use case. Wait for a maintainer to agree on the API shape there before you implement it. Context is more useful to us than code at this stage.
- **Already specified?** If a published [sdk-spec](https://github.com/PostHog/sdk-specs) defines the API, that's the agreement, so you don't need an issue.
- **Already have a PR open?** Don't stop or rewrite it. Call out the public API change at the top of the PR description, and link or open an issue so we can discuss the shape there.
- Check first whether an existing option or hook, such as `beforeSend`, already covers the use case. We avoid offering two ways to do the same thing.
- If a reviewer suggests a different API on your PR, confirm it with them before re-implementing. Treat it as a question, not an instruction.

`make api` regenerates the `api/*.api` files for `posthog`, `posthog-android`, and `posthog-server`. A diff in those files means your change touches public API.

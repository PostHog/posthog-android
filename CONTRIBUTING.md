# Contributing

If you would like to contribute code to `posthog-android` you can do so through GitHub by forking the repository and opening a pull request against `main`.

## Commit signing

This repo requires all commits to be signed. To configure commit signing, see the [PostHog handbook](https://posthog.com/handbook/engineering/security#commit-signing). Set `commit.gpgsign` to `true` in your git config so that every commit is signed, not only the ones you sign by hand.

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

## Replay masking image snapshots

`make maskingSnapshots` verifies the committed PNGs in `posthog-android/src/test/resources/snapshots/replay-masking/`. Each image shows the original native View fixture beside the SDK-masked result. The suite covers defaults, global masking options, explicit mask/unmask markers, scrolling, scaling and dialog-local coordinates. The single-window fixtures use the production mask collection and painter without PixelCopy scheduling or image encoding.

The stacked-dialog golden exercises registered Activity/dialog windows through the scene producer, masking and image encoding. Its PixelCopy shadow draws each native window into the SDK's target bitmap. Both panels rasterize the emitted screenshot/dim wireframes in SDK order and coordinates, before and after enabling text masking; this checks producer composition, not rrweb playback. It covers an Activity beneath two overlapping dialogs, topmost-window dimming and masking across all three windows.

The renderer is pinned to Robolectric API 28, native graphics, mdpi, US English and light mode. Verification compares decoded pixels exactly and never creates missing baselines. On failure, expected/actual/diff PNGs are written to `posthog-android/build/reports/replay-masking-snapshots/` and uploaded by CI. The tests also run with the normal Android unit suite.

For intentional changes, run `make recordMaskingSnapshots`, inspect both panels of every changed image for under- or over-masking, then run `make maskingSnapshots` and commit the updated PNGs. Recording overwrites the selected baselines, so use it only after understanding the visual change.

## Public API changes

Public API is hard to change once it ships, so agree on it before writing the implementation. Our [SDK guidelines](https://posthog.com/handbook/engineering/sdks/guidelines) explain how we design it.

This section is for external contributors. PostHog maintainers (members of the PostHog GitHub org) agree on API shape in the PR itself, so they don't need a separate issue.

- **Before you start:** if you need something the SDK doesn't support and it would add or change a public option, method, or type, open an issue describing your use case. Wait for a maintainer to agree on the API shape there before you implement it. Context is more useful to us than code at this stage.
- **Already specified?** If a published [sdk-spec](https://github.com/PostHog/sdk-specs) defines the API, that's the agreement, so you don't need an issue.
- **Already have a PR open?** Don't stop or rewrite it. Call out the public API change at the top of the PR description, and link or open an issue so we can discuss the shape there.
- Check first whether an existing option or hook, such as `beforeSend`, already covers the use case. We avoid offering two ways to do the same thing.
- If a reviewer suggests a different API on your PR, confirm it with them before re-implementing. Treat it as a question, not an instruction.

`make api` regenerates the `api/*.api` files for `posthog`, `posthog-android`, and `posthog-server`. A diff in those files means your change touches public API.

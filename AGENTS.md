# AGENTS.md — PostHog Android SDK

## Project overview

PostHog Android SDK monorepo. Kotlin-first, targets JVM and Android.

### Module structure

- `posthog/` — Core SDK (pure Kotlin/JVM, no Android dependencies)
- `posthog-android/` — Android-specific SDK (depends on `posthog/`)
- `posthog-android-surveys-compose/` — Compose survey UI (depends on `posthog-android/`, transitively on `posthog/`)
- `posthog-server/` — Server-side SDK (depends on `posthog/`)
- `posthog-server-openfeature/` — OpenFeature provider for the server-side SDK (depends on `posthog-server/`, Java 11+)
- `posthog-android-gradle-plugin/` — Gradle plugin for Android integrations
- `posthog-samples/` — Sample apps

## Validation

Prefer Make wrappers; see [development commands](./CONTRIBUTING.md#development-commands) for focused tests and formatting. Select JVM, Android or Compose survey checks for the affected module/behavior; Markdown-only edits need documentation checks, not SDK builds/tests.

## Code style

- Kotlin, formatted via **Spotless + ktlint**
- No consecutive KDoc comments (ktlint `no-consecutive-comments` rule)
- Use `@PostHogInternal` annotation for public APIs meant only for internal SDK use
- `internal` visibility for utilities not exposed to consumers
- Prefer top-level functions over singleton objects for stateless utilities
- Preserve established ownership and synchronization. Use `@Volatile` for independent-state visibility where appropriate; protect compound state and mutable collections with established locks/atomics. A volatile reference does not protect mutable contents.
- Test files live alongside source in `src/test/java/` mirroring the main package structure
- Test fixtures (JSON) go in `src/test/resources/json/`

## Testing

- Use MockWebServer (`okhttp3.mockwebserver`) for HTTP tests and `PostHogMemoryPreferences` for in-memory preferences
- Await relevant async work before assertions. `awaitExecution()` is a nonterminal barrier only for previously submitted work on a serial executor, not arbitrary concurrent/chained work; otherwise use explicit completion signals. Use `shutdownAndAwaitTermination()` for terminal waits/cleanup; it disables future submissions.
- Close SDK instances with `sut.close()` (or `PostHog.close()` for singleton tests) and shut down any created mock servers with `http.shutdown()` in test cleanup

## PR process

- Public API changes: follow "Public API changes" in [CONTRIBUTING.md](./CONTRIBUTING.md). As an agent, also:
    - When reviewing or fixing someone else's PR, don't ask for or open an issue. Note an external contributor's public API change when it has neither an agreed issue nor an API-defining published spec.
    - The author is a PostHog maintainer when the PR's `author_association` is `MEMBER` or `OWNER` (`gh api repos/PostHog/posthog-android/pulls/<number> --jq .author_association`) or, before a PR exists, when `gh api orgs/PostHog/members/$(gh api user --jq .login)` succeeds. If the check fails or can't run, treat the author as an external contributor.
    - A published [sdk-spec](https://github.com/PostHog/sdk-specs) that defines the API counts as the agreement, so no issue is needed.
    - For an external contributor with no agreed issue and no spec, stop before implementing and draft the issue body for the user to post. Open it only if they ask.
- Before implementing or reviewing SDK behavior, check [PostHog/sdk-specs](https://github.com/PostHog/sdk-specs) for a spec covering it (its README lists every capability). If one exists, use it as the cross-SDK contract for the behavior the PR changes, and call out any divergence in that behavior in the PR description. Don't fix or flag discrepancies between the spec and code the PR doesn't touch. If none exists, carry on.

Before opening a PR, run `pnpm changeset`, select affected packages, semver bump and change summary, and commit the generated `.changeset/*.md` with the PR.

Use [.github/pull_request_template.md](./.github/pull_request_template.md); explain change intent and actual validation, and follow its Agent context, attribution and human-review requirements.

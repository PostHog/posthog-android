# AGENTS.md — PostHog Android SDK

## Project overview

PostHog Android SDK monorepo. Kotlin-first, targets JVM and Android.

### Module structure

- `posthog/` — Core SDK (pure Kotlin/JVM, no Android dependencies)
- `posthog-android/` — Android-specific SDK (depends on `posthog/`)
- `posthog-server/` — Server-side SDK (depends on `posthog/`)
- `posthog-android-gradle-plugin/` — Gradle plugin for Android integrations
- `posthog-samples/` — Sample apps

## Commands

Always use Makefile targets when possible:

| Task | Command |
|---|---|
| Build all | `make compile` |
| Run all tests (Java + Android) | `make test` |
| Run core (JVM) tests only | `make testJava` |
| Run a specific test class | `./gradlew :posthog:test --tests "com.posthog.internal.SomeTest"` |
| Check formatting | `make checkFormat` |
| Fix formatting | `make format` |
| Clean | `make clean` |
| Dump API surface | `make api` |
| Test coverage report | `make testReport` |
| Dry release (local maven) | `make dryRelease` |
| Stop Gradle daemons | `make stop` |

## Code style

- Kotlin, formatted via **Spotless + ktlint** (`make format` / `make checkFormat`)
- No consecutive KDoc comments (ktlint `no-consecutive-comments` rule)
- Use `@PostHogInternal` annotation for public APIs meant only for internal SDK use
- `internal` visibility for utilities not exposed to consumers
- Prefer top-level functions over singleton objects for stateless utilities
- Use `@Volatile` for fields accessed across threads; `synchronized` blocks for compound operations
- Test files live alongside source in `src/test/java/` mirroring the main package structure
- Test fixtures (JSON) go in `src/test/resources/json/`

## Testing

- JUnit + kotlin.test assertions
- MockWebServer (`okhttp3.mockwebserver`) for HTTP tests
- In-memory preferences (`PostHogMemoryPreferences`) for unit tests
- Executors are shut down with `executor.shutdownAndAwaitTermination()` to ensure async work completes before assertions
- Always call `sut.clear()` and `http.shutdown()` in test cleanup

## PR process

Use this template when creating PRs:

```
## :bulb: Motivation and Context
<!--- Why is this change required? What problem does it solve? -->
<!--- If it fixes an open issue, please link to the issue here. -->


## :green_heart: How did you test it?


## :pencil: Checklist
<!--- Put an `x` in the boxes that apply -->

- [ ] I reviewed the submitted code.
- [ ] I added tests to verify the changes.
- [ ] I updated the docs if needed.
- [ ] No breaking change or entry added to the changelog.

### If releasing new changes

- [ ] Ran `pnpm changeset` to generate a changeset file
- [ ] Added the "release" label to the PR to indicate we're publishing new versions for the affected packages
```

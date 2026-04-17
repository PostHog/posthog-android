# Contributing

This package contains the PostHog Android SDK compliance adapter used with the PostHog SDK Test Harness.

## Prerequisites

- x86_64 architecture for the full local test flow because of the Java 8 dependency
- Java 8, 11, and 17 for local Gradle builds

On Apple Silicon, Docker will use emulation. It is slower, but it works.

## Building

### Local build

```bash
./gradlew :sdk_compliance_adapter:build
```

### Docker build

```bash
docker build -f sdk_compliance_adapter/Dockerfile -t posthog-android-adapter .
```

The Dockerfile uses Gradle toolchain auto-download to fetch the required Java versions.

## Running tests

Tests run automatically in GitHub Actions on pushes to `main`/`master`, pull requests, and manual workflow dispatches.

### Local Docker Compose run

```bash
cd sdk_compliance_adapter
docker-compose up --build --abort-on-container-exit
```

This runs:

- `test-harness` - compliance test runner
- `adapter` - this SDK adapter
- `mock-server` - mock PostHog server

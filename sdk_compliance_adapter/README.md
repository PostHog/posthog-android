# PostHog Android SDK Compliance Adapter

This compliance adapter wraps the PostHog Android SDK and exposes a standardized HTTP API for automated compliance testing using the [PostHog SDK Test Harness](https://github.com/PostHog/posthog-sdk-test-harness).

## Quick Start

### Running Tests in CI (Recommended)

Tests run automatically in GitHub Actions on:
- Push to `main`/`master` branch
- Pull requests
- Manual trigger via `workflow_dispatch`

See `.github/workflows/sdk-compliance-tests.yml`

### Running Tests Locally

**Note:** Requires x86_64 architecture due to Java 8 dependency. On Apple Silicon, Docker will use emulation (slower but works).

```bash
cd sdk_compliance_adapter
docker-compose up --build --abort-on-container-exit
```

## Architecture

- **adapter.kt** - Ktor HTTP server implementing the compliance adapter API
- **TrackingInterceptor** - OkHttp interceptor for monitoring SDK HTTP requests
- **Dockerfile** - Multi-stage Docker build (requires x86_64 for Java 8)
- **docker-compose.yml** - Local test orchestration

## SDK Modifications

To enable request tracking, we added an optional `httpClient` parameter to `PostHogConfig`:

```kotlin
// PostHogConfig.kt
public var httpClient: okhttp3.OkHttpClient? = null
```

This allows the test adapter to inject a custom OkHttpClient with tracking interceptors. **This change is fully backward compatible** - existing code works unchanged.

## Implementation Details

### HTTP Request Tracking

The adapter injects a custom OkHttpClient that:
- Intercepts all `/batch/` requests
- Extracts event UUIDs from request bodies
- Tracks status codes, retry attempts, and event counts

### Event Tracking

Events are tracked via:
1. `beforeSend` hook - Captures UUIDs as events are queued
2. HTTP interceptor - Verifies UUIDs in outgoing requests

### SDK Type

The Android SDK uses **server SDK format**:
- Endpoint: `/batch/`
- Format: `{api_key, batch, sent_at}`

Tests run with `--sdk-type server`.

## Files Created

```
sdk_compliance_adapter/
├── adapter.kt              # Main adapter implementation
├── build.gradle.kts        # Gradle build configuration
├── Dockerfile              # Docker build (x86_64)
├── docker-compose.yml      # Local test setup
├── README.md               # This file
└── IMPLEMENTATION_NOTES.md # Detailed technical notes
```

## Changes to Core SDK

### posthog/src/main/java/com/posthog/PostHogConfig.kt
- Added `httpClient: OkHttpClient?` parameter

### posthog/src/main/java/com/posthog/internal/PostHogApi.kt
- Modified to use injected `httpClient` if provided

### settings.gradle.kts
- Added `:sdk_compliance_adapter` module

### .github/workflows/sdk-compliance-tests.yml
- GitHub Actions workflow for automated testing

## References

- [Test Harness Repository](https://github.com/PostHog/posthog-sdk-test-harness)
- [Adapter Guide](https://github.com/PostHog/posthog-sdk-test-harness/blob/main/ADAPTER_GUIDE.md)
- [Contract Specification](https://github.com/PostHog/posthog-sdk-test-harness/blob/main/CONTRACT.yaml)
- [Browser SDK Adapter](https://github.com/PostHog/posthog-js/tree/main/packages/browser/sdk_compliance_adapter) (Reference)

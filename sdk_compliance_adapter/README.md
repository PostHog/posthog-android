# PostHog Android SDK Compliance Adapter

This compliance adapter wraps the PostHog Android SDK and exposes a standardized HTTP API for automated compliance testing using the [PostHog SDK Test Harness](https://github.com/PostHog/posthog-sdk-test-harness).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for local build and compliance test instructions.

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
- Intercepts all `/i/v1/analytics/events` requests
- Extracts event UUIDs from request bodies
- Tracks status codes, retry attempts, and event counts

### Event Tracking

Events are tracked via:
1. `beforeSend` hook - Captures UUIDs as events are queued
2. HTTP interceptor - Verifies UUIDs in outgoing requests

### SDK Type

The Android SDK advertises `capture_v1` and `encoding_gzip`:
- Endpoint: `POST /i/v1/analytics/events`
- Format: `{created_at, batch}` with the project token in an `Authorization: Bearer` header

Tests run with `--sdk-type server`; the harness selects the `capture_v1` suite from the capabilities.

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

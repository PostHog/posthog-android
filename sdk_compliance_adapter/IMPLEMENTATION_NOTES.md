# PostHog Android SDK Compliance Adapter - Implementation Notes

## Overview

This directory contains the compliance test adapter for the PostHog Android SDK. The adapter wraps the SDK and exposes a standardized HTTP API for automated compliance testing.

## Architecture

### Components

1. **adapter.kt** - Ktor-based HTTP server that implements the compliance adapter API
2. **TrackingInterceptor** - OkHttp interceptor that monitors HTTP requests made by the SDK
3. **AdapterState** - Tracks captured events, sent events, retries, and request metadata
4. **Docker** - Containerized build and runtime environment

### Key Implementation Details

#### HTTP Request Tracking

The adapter uses a custom OkHttpClient with a `TrackingInterceptor` that:
- Intercepts all HTTP requests to the `/batch/` endpoint
- Parses request bodies to extract event UUIDs using regex
- Tracks request count, status codes, retry attempts, and event counts
- Updates adapter state with request metadata

#### Event UUID Tracking

Events are tracked using two mechanisms:
1. **beforeSend hook** - Captures UUIDs as events are queued
2. **HTTP interceptor** - Extracts UUIDs from outgoing HTTP requests

This dual approach ensures UUIDs are available immediately when events are captured AND verified when actually sent.

#### SDK Configuration

The adapter configures the PostHog SDK for optimal testing:
- `flushAt = 1` - Send events immediately (or as configured)
- `flushIntervalSeconds` - Fast flush intervals for tests
- `debug = true` - Enable logging
- `httpClient` - Custom OkHttpClient with tracking interceptor

## SDK Modifications Required

To enable HTTP request tracking, the following changes were made to the core SDK:

### PostHogConfig.kt

Added optional `httpClient` parameter:

```kotlin
public var httpClient: okhttp3.OkHttpClient? = null
```

This allows test adapters to inject a custom OkHttpClient with interceptors.

### PostHogApi.kt

Modified to use injected client if provided:

```kotlin
private val client: OkHttpClient =
    config.httpClient ?: OkHttpClient.Builder()
        .proxy(config.proxy)
        .addInterceptor(GzipRequestInterceptor(config))
        .build()
```

**These changes are backward compatible** - existing code works unchanged.

## Building

### Local Build (requires Java 8, 11, and 17)

```bash
./gradlew :sdk_compliance_adapter:build
```

### Docker Build (recommended)

```bash
docker build -f sdk_compliance_adapter/Dockerfile -t posthog-android-adapter .
```

The Dockerfile uses Gradle toolchain auto-download to fetch required Java versions.

## Running Tests

### With Docker Compose

```bash
cd sdk_compliance_adapter
docker-compose up --build --abort-on-container-exit
```

This runs:
- **test-harness** - Compliance test runner
- **adapter** - This SDK adapter
- **mock-server** - Mock PostHog server

### SDK Type

The Android SDK uses **server SDK format**:
- Endpoint: `/batch/`
- Format: `{api_key: "...", batch: [{event}, {event}], sent_at: "..."}`

Tests run with `--sdk-type server` flag.

## API Endpoints

The adapter implements the standard compliance adapter API:

- `GET /health` - Health check with SDK version info
- `POST /init` - Initialize SDK with config
- `POST /capture` - Capture a single event
- `POST /flush` - Force flush all pending events
- `GET /state` - Get adapter state for assertions
- `POST /reset` - Reset SDK and adapter state

See [test-harness CONTRACT.yaml](https://github.com/PostHog/posthog-sdk-test-harness/blob/main/CONTRACT.yaml) for full API spec.

## Testing Philosophy

The adapter tests the **core PostHog SDK** (`:posthog` module) which contains:
- All HTTP communication logic
- Retry behavior with exponential backoff
- Event batching and queueing
- Error handling

The `:posthog-android` module is a thin wrapper that adds Android-specific features (lifecycle tracking, etc.) but doesn't change the core compliance behavior.

## Known Limitations

### Java 8 on ARM64

Java 8 is not available for ARM64 (Apple Silicon). The project requires Java 8 for the core module. Solutions:

1. **Docker** (recommended) - Uses Gradle toolchain auto-download
2. **CI/CD** - GitHub Actions provides Java 8 for Linux x64
3. **Modify core** - Upgrade to Java 11 (not recommended - breaks compatibility)

### Flush Timing

The `/flush` endpoint includes a 2-second wait to account for:
- SDK's internal flush timer
- Network latency in Docker environment
- Mock server processing time

This may need adjustment based on test results.

## Future Improvements

1. **Reduce flush wait time** - Profile actual flush timing and optimize
2. **Add compression support** - Currently the adapter doesn't test gzip compression
3. **More detailed error tracking** - Capture and report SDK errors in state
4. **Performance metrics** - Track request timing, payload sizes

## References

- [Test Harness Repository](https://github.com/PostHog/posthog-sdk-test-harness)
- [Browser SDK Adapter](../../posthog-js/packages/browser/sdk_compliance_adapter/) - Reference implementation
- [Adapter Guide](https://github.com/PostHog/posthog-sdk-test-harness/blob/main/ADAPTER_GUIDE.md)
- [Contract Specification](https://github.com/PostHog/posthog-sdk-test-harness/blob/main/CONTRACT.yaml)

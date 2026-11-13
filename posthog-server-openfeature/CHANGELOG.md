## Next

## 0.1.3

### Patch Changes

- b3afe48: Treat failed detailed feature flag records as unavailable values and payloads, independently of response-wide errors. Preserve successful cached values on errored client refreshes and same-context local decisions during server fallback. Report failed direct reads as evaluation failures without tracking caller defaults as evaluated responses.

## 0.1.2

### Patch Changes

- 6a80525: Avoid unnecessary Calendar allocations when reading device-clock timestamps.

## 0.1.1

### Patch Changes

- fb584fd: Upgrade OkHttp to 4.12.1 to block compromised eMudhra intermediate certificates.

## 0.1.0

### Minor Changes

- 4580464: Add `PostHogProvider`, an OpenFeature provider for the PostHog server-side JVM SDK

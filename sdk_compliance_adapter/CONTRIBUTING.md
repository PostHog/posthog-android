# SDK compliance profiles

These adapters run the repository's SDK source with harness **1.0.0**, contract 1.2.
Each profile selects **30 capture V0 + 17 feature flag tests**, server wire, gzip.
Assertions are advisory; missing, empty, or incomplete reports fail CI.

| Profile | Actual entry and runtime |
| --- | --- |
| `core` | `com.posthog.PostHog.with` on JVM, integrated context and file queue |
| `server` | `com.posthog.server.PostHog.with` on JVM, SDK memory queue |
| Android entry | `PostHogAndroid.with(applicationContext, config)` in an emulator APK |

Android uses native context, networking, preferences and date providers. Its report
is separate from the core integration and Java server reports. CI uses Android 35
x86_64; the APK requires API 26 or newer for the adapter's Java time/HTTP runtime.

## Configuration and observation

Capture forwards identity, properties and an optional parsed `Date` to the public
SDK. The SDK owns event creation, UUIDs, serialization, compression and retry policy.
The before-send hook observes the final UUID produced by a public call (stateful
capture also builds an intermediate event). It does not assign or edit IDs.

A passive, per-initialization HTTP ingress forwards the SDK's request bytes and
end-to-end headers to the mock, and returns its response unchanged. It does not
retry or follow redirects. The SDK retains its default HTTP client. Host and
hop-by-hop framing headers necessarily belong to each proxy connection. The
controller and proxy use Ktor CIO on both JVM and Android.

`/flush` calls the SDK once and waits up to ten seconds for acknowledgments. It
returns `success: false` if any observed event is still unacknowledged, including
terminal drops: there is no public queue-completion API. `/state.pending_events`
therefore means **observed, unacknowledged UUIDs**, not native queue depth. Retries
are observed repeated UUIDs; a retry budget is never used to infer completion.
Reset closes the instance, retires its ingress and clears this test app's storage.
Parallel tests are not supported.

The configured flush threshold defaults to 100. Millisecond intervals default to
500 and round up to whole seconds (minimum one second), the SDK's supported unit.
Java server has no public `maxRetries` setting; that input cannot be forwarded.
Gzip is the only profile: the SDK has no public compression-disable switch here.
V1 and dedicated AI capture are not advertised.

### Flags

Core and Android disable flag preload, and Android additionally disables lifecycle,
screen, deep-link and push autocapture. Remote configuration still uses the SDK's
startup path. These are isolated **reload-per-action** profiles, not certification
of default startup/preload behavior or ordinary cached-getter network behavior.

Person/group properties use public setters. Identity and groups use public
`identify`/`group`, retaining their SDK-owned events and reloads. The adapter awaits
those reload callbacks; otherwise it explicitly reloads and reads the public cached
getter. No controller parses flag responses or manufactures called-events.
Once identified, changing to another user requires adapter reset/init; unsupported
identity transitions are rejected before changing SDK state or waiting for a reload.
Multi-group actions can cause multiple SDK requests. Singleton flag-key scope,
per-call GeoIP overrides and compound person-property assertions are deferred
stateful/server-contract differences, but remain selected and visible in reports.

Java uses public `evaluateFlags` with singleton keys, groups, person/group properties
and the supported GeoIP argument, then reads `snapshot.getFlag`. Local evaluation
is disabled and `featureFlagCacheSize=0`, so this is an explicit remote/no-cache
profile regardless of `force_remote`.

The following Java result assertions remain deferred because their legacy-only
`featureFlags` fixtures do not match the SDK's rich v2 parser. They are **not skipped**:

- `feature_flags.request_lifecycle.mock_response_value_is_returned_to_caller`
- `feature_flags.retry_behavior.retries_flags_on_502`
- `feature_flags.retry_behavior.retries_flags_on_504`
- `feature_flags.side_effect_events.get_feature_flag_captures_feature_flag_called_event`

All capture retries/status cases exercise real transports, including Java's
existing 408/429 retention, retry-budget and Retry-After differences. Its slower
retry cadence can also miss fixed harness wait windows. A passing assertion does
not imply these underlying SDK policies are compliant.

## Local JVM build and run

Use JDK 17 and the repository Gradle wrapper. Adapter projects are opt-in:

```sh
./gradlew -Pcompliance :sdk_compliance_adapter:test :sdk_compliance_adapter:installDist
PORT=18290 SDK_PROFILE=core sdk_compliance_adapter/build/install/sdk_compliance_adapter/bin/sdk_compliance_adapter
# Use SDK_PROFILE=server for Java server.
```

Against that process, run the pinned harness (Linux with Docker):

```sh
bash sdk_compliance_adapter/run-harness.sh 18290 19290
python3 sdk_compliance_adapter/check-report.py report/compliance.json
```

Alternatively build `sdk_compliance_adapter/Dockerfile`, or run Compose from this
directory with `SDK_PROFILE=core` or `SDK_PROFILE=server`. Compose's private network
ports are not published to the host. Docker Compose does not emulate Android.

## Android emulator

With an Android SDK and a running emulator:

```sh
CI=false ./gradlew -Pcompliance -PcomplianceAndroid :sdk_compliance_adapter:android:assembleDebug
# Linux + Docker; set ANDROID_SERIAL if other devices are attached.
bash sdk_compliance_adapter/run-android.sh
```

The script installs the test APK, starts its HTTP listener, and forwards adapter
port 18292 and reverses mock port 19292. For a native harness installation on macOS,
use the same `adb install`, `forward`, `reverse` and `am start` commands in the script,
then run harness 1.0.0 with those URLs and `--sdk-type server --concurrency 1`.
Only use local/mock project keys; no live PostHog project is needed.

Adapter regression tests cover both public JVM entries, Date/UUID wire fidelity,
real flag parsing/called-events and passive proxy handling of the trailing-slash
flags endpoint. Test fixtures include a genuine rich v2 response independently of
the harness's legacy-only fixtures.

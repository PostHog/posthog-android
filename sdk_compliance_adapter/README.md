# PostHog Android / Java compliance adapters

The adapter exposes three public SDK profiles:

| Profile | SDK entry point | Flag model |
| --- | --- | --- |
| JVM core | `com.posthog.PostHog.with(config)` | Stateful client |
| Native Android | `PostHogAndroid.with(applicationContext, config)` | Stateful client |
| Java server | `com.posthog.server.PostHog.with(config)` | Identity and context per evaluation |

`common/` owns the Ktor routes, passive ingress proxy and stateful client mapping.
`src/` supplies the JVM launcher and Java server mapping. `android/` contains the
native Activity host. SDK HTTP requests and responses pass through the proxy
unchanged; native SDK code owns serialization, retries and called-events.

## Client flag lifecycle

Core and native Android advertise `bootstrap_identity` and `client_feature_flags`.
An optional `/init` `distinct_id` sets public `PostHogBootstrapConfig(distinctId = id,
isIdentifiedId = true)` before constructing the SDK, using fresh owned storage.
Blank IDs return HTTP 400. Initialization completes inside `/init`. Captures without
an explicit per-event identity then use this initialized identity.

The client test flow is:

1. `/init` establishes identity, with flag preload disabled.
2. `POST /reload_feature_flags` calls public `reloadFeatureFlags` and waits for its
   callback (up to 15 seconds). It does not identify or read a flag.
3. `POST /get_cached_feature_flag` with `{"key":"my-flag"}` calls public
   `getFeatureFlag`, preserving native called-events without a reload or context change.
4. `/flush` invokes the SDK flush and waits for observed acknowledgments.

A harness supporting these operations selects ten client lifecycle cases with
`--sdk-type client --suite feature_flags`. They cover identity, request shape, cache
reuse/replacement, multiple keys, native 502/504 retries and called-events. They do
not establish default preload behavior, group/context updates, or GeoIP configuration.

The legacy `/get_feature_flag` route still accepts per-call context for older callers.
Without bootstrap it uses public `identify` and waits for the reload that operation
already triggers; bootstrap avoids the merge event rather than removing a redundant
reload from that path. A different identified user requires a new `/init`.

## Java server

Java continues to call `evaluateFlags` with each request's identity and evaluation
context. It advertises neither client capability and rejects client bootstrap init.
Its 17 server flag cases remain selected with `--sdk-type server`.

## CI and validation

The current CI scripts still pin harness 1.0.0 and its legacy 30 capture + 17 server
flag inventory for all three profiles. Migrating the client jobs requires releasing
and adopting the client lifecycle contract and updating report inventories together.
A `/batch` wire format does not make the core or Android SDK a server SDK.

See [CONTRIBUTING.md](CONTRIBUTING.md) for the existing build/run commands.
JVM core execution does not certify the native Activity, Android device behavior,
or Java server semantics. Reports and validation must identify the executed profile.

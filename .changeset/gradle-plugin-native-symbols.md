---
'posthog-android-gradle-plugin': minor
---

Upload native (`.so`) debug symbols via `posthog-cli symbol-sets upload` (>= 0.7.32), so native crash stack traces can be symbolicated. Opt in with the new plugin extension:

```kotlin
posthog {
    uploadNativeSymbols.set(true)
}
```

When enabled, a new `uploadPostHogNativeSymbols<Variant>` task reads the variant's unstripped merged native libs (NDK builds, `jniLibs`, and libraries packaged by dependencies) and runs automatically after `assemble`/`install`/`bundle` for non-debuggable variants, minified or not. Set `includeNativeSymbolSources.set(true)` to also bundle the project sources referenced by the debug info (off by default). The task can always be invoked explicitly, for any variant, without opting in.

---
'posthog-android-gradle-plugin': minor
---

Upload native (`.so`) debug symbols via `posthog-cli symbol-sets upload` (>= 0.7.32), so native crash stack traces can be symbolicated. Opt in with the new plugin extension:

```kotlin
posthog {
    uploadNativeSymbols.set(true)
}
```

When enabled, a new `uploadPostHogNativeSymbols<Variant>` task reads the variant's unstripped merged native libs (NDK builds, `jniLibs`, and libraries packaged by dependencies) and runs automatically after `assemble`/`install`/`bundle`, for minified and non-minified variants alike. The task can also be invoked explicitly without opting in.

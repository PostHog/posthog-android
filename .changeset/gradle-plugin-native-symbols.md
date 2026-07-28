---
'posthog-android-gradle-plugin': minor
---

Upload native (`.so`) debug symbols via `posthog-cli symbol-sets upload` (>= 0.7.32), so native crash stack traces can be symbolicated. A new `uploadPostHogNativeSymbols<Variant>` task reads the variant's unstripped merged native libs and runs automatically after `assemble`/`install`/`bundle` when the app module builds native code; apps that only bundle prebuilt `.so` files from dependencies can invoke it explicitly. Unlike mapping upload, the task also runs for non-minified variants.

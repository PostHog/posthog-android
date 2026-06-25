---
'posthog-android': patch
---

Allow the posthog-kmp wrapper SDK to keep its own `sdkName`/`sdkVersion` so KMP events report the correct `$lib`/version, mirroring how posthog-flutter and posthog-react-native are handled.

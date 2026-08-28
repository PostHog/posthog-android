---
'posthog-android-gradle-plugin': patch
---

Stop publishing `org.jetbrains.kotlin:kotlin-gradle-plugin`, so your build compiles with the Kotlin version it declares — a build that relied on this plugin to supply it must now declare it itself.

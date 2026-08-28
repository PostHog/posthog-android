---
'posthog-android-gradle-plugin': patch
---

Stop forcing `org.jetbrains.kotlin:kotlin-gradle-plugin` onto the consuming build's buildscript classpath, which raised the Kotlin version every module compiles with.

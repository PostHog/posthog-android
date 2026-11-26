---
"posthog-server": minor
---

Read the map id from `posthog-meta.properties` on the classpath when `releaseIdentifier` isn't set, so exception frames carry the id that `posthog-maven-plugin`, the `com.posthog.jvm` Gradle plugin or `posthog-cli jvm upload --meta-out` wrote at build time. An explicit `releaseIdentifier` still wins.

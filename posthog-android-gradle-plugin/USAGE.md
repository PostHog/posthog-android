```kotlin
plugins {
    id("com.android.application")
    kotlin("android")
    id("com.posthog.android") // <- add this plugin
}

// Optional configuration
tasks.withType<PostHogCliExecTask> {
  // Custom CLI location
  // defaults to globally installed posthog-cli on the PATH
  postHogExecutable = "/path/to/posthog-cli"
  
  // Authentication parameters
  // otherwise the CLI must be pre-authenticated
  postHogHost = "https://eu.posthog.com"
  postHogProjectId = "my-project-id"
  postHogApiKey = "my-personal-api-key"
}
```

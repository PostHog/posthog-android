# PostHog Java Sample

Interactive Java example demonstrating the PostHog Server SDK.

## Features

This sample demonstrates:

- **Event Capture** - Track user actions and custom events
- **User Identification** - Identify users and set properties
- **Group Analytics** - Associate users with companies/organizations
- **Feature Flags** - Remote and local evaluation
- **Local Evaluation with ETag Polling** - Bandwidth-efficient flag polling
- **Error Tracking** - Capture and report exceptions

## Setup

1. Copy the example properties file and configure your API keys:

   ```bash
   cp src/main/resources/application.properties.example src/main/resources/application.properties
   ```

2. Edit `application.properties` with your PostHog project API key:

   ```properties
   posthog.api.key=phc_your_project_api_key_here
   posthog.host=https://us.i.posthog.com
   ```

3. (Optional) For local evaluation, set your personal API key:

   ```bash
   export POSTHOG_PERSONAL_API_KEY=phx_your_personal_api_key_here
   ```

   Personal API keys can be created at: <https://us.posthog.com/settings/user-api-keys>

## Running the Example

```bash
./gradlew :posthog-samples:posthog-java-sample:run --console=plain
```

You'll see an interactive menu:

```text
PostHog Java SDK Demo - Choose an example to run:

1. Capture events
2. Identify users
3. Feature flags (remote evaluation)
4. Local evaluation with ETag polling
5. Run all examples
6. Exit
```

## Examples

### 1. Capture Events

Demonstrates basic event tracking with properties.

### 2. Identify Users

Shows user identification, group association, and aliasing.

### 3. Feature Flags (Remote Evaluation)

Fetches feature flag values from the PostHog API.

### 4. Local Evaluation with ETag Polling

Demonstrates local feature flag evaluation with ETag support for bandwidth optimization.
This example polls every 5 seconds for 30 seconds so you can observe:

- First poll: Full response with flag definitions
- Subsequent polls: HTTP 304 Not Modified (if flags unchanged)

Watch the debug logs for messages like:

```text
Feature flags not modified (304), using cached data
```

### 5. Run All Examples

Runs all examples in sequence.

## Requirements

- Java 8 or higher
- PostHog project API key
- (Optional) Personal API key for local evaluation

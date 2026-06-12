# PostHog Java sample

Please see the main [PostHog docs](https://posthog.com/docs).

SDK usage examples and code snippets live in the official documentation so they stay up to date.

## Documentation

- [Java library docs](https://posthog.com/docs/libraries/java)

## Local setup

Copy the example properties file and add your PostHog project API key:

```bash
cp src/main/resources/application.properties.example src/main/resources/application.properties
```

```properties
posthog.api.key=phc_your_project_api_key_here
posthog.host=https://us.i.posthog.com
```

For local feature flag evaluation, export a personal API key:

```bash
export POSTHOG_PERSONAL_API_KEY=phx_your_personal_api_key_here
```

Run the interactive sample from the repository root:

```bash
./gradlew :posthog-samples:posthog-java-sample:run --console=plain
```

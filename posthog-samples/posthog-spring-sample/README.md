# PostHog Spring sample

Please see the main [PostHog docs](https://posthog.com/docs).

SDK usage examples and code snippets live in the official documentation so they stay up to date.

## Documentation

- [Java library docs](https://posthog.com/docs/libraries/java)

## Local setup

Update `src/main/resources/application.properties` with your PostHog project API key:

```properties
posthog.api.key=phc_your_project_api_key_here
posthog.host=https://us.i.posthog.com
```

Run the Spring Boot sample from the repository root:

```bash
./gradlew :posthog-samples:posthog-spring-sample:bootRun
```

Then trigger the sample endpoint:

```bash
curl -X POST http://localhost:8080/api/action
```

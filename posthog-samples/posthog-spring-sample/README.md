# PostHog Spring Sample

A sample Spring Boot application demonstrating how to integrate the PostHog server-side SDK as a Spring-managed bean.

## Overview

This sample shows how to:

- Configure PostHog as a Spring Bean using dependency injection
- Inject the PostHog client into Spring controllers
- Capture events from REST API endpoints
- Configure PostHog via Spring properties

## Project Structure

```
src/main/java/com/posthog/spring/sample/
├── PostHogSpringSampleApplication.java  # Spring Boot application entry point
├── PostHogConfiguration.java            # PostHog bean configuration
└── ActionController.java                # REST controller that captures events
```

## Configuration

### PostHogConfiguration.java

The `PostHogConfiguration` class creates a Spring-managed PostHog bean:

```java
@Configuration
public class PostHogConfiguration {
    @Value("${posthog.api.key:phc_YOUR_API_KEY_HERE}")
    private String apiKey;

    @Value("${posthog.host:https://us.i.posthog.com}")
    private String host;

    @Bean(destroyMethod = "close")
    public PostHogInterface posthog() {
        PostHogConfig config = PostHogConfig
                .builder(this.apiKey)
                .host(this.host)
                .debug(true)
                .build();

        return PostHog.with(config);
    }
}
```

Key points:

- The `@Bean` annotation makes PostHog available for dependency injection
- `destroyMethod = "close"` ensures proper cleanup when the application shuts down
- Configuration values are read from `application.properties`

### application.properties

Configure PostHog settings in `src/main/resources/application.properties`:

```properties
# PostHog Configuration
posthog.api.key=phc_YOUR_API_KEY_HERE
posthog.host=https://us.i.posthog.com

# Spring Boot Configuration
spring.application.name=posthog-spring-sample
logging.level.com.posthog=DEBUG
```

## Setup

1. Update `application.properties` with your PostHog API key:

   ```properties
   posthog.api.key=phc_your_actual_api_key
   ```

2. (Optional) Change the host if using a self-hosted instance:

   ```properties
   posthog.host=https://your-posthog-instance.com
   ```

3. Build and run the application:
   ```bash
   ./gradlew :posthog-samples:posthog-spring-sample:bootRun
   ```

The application will start on port 8080 by default.

## Usage

### Capturing Events

The sample provides a REST endpoint that captures a PostHog event:

```bash
curl -X POST http://localhost:8080/api/action
```

This will:

1. Generate a distinct user ID (in production, use a real user identifier)
2. Capture an event named `action_performed` with properties `{ "source": "api" }`
3. Return a 200 OK response

### Controller Implementation

The `ActionController` demonstrates dependency injection of the PostHog client:

```java
@RestController
@RequestMapping("/api")
public class ActionController {

    private final PostHogInterface postHog;

    public ActionController(PostHogInterface postHog) {
        this.postHog = postHog;
    }

    @PostMapping("/action")
    public ResponseEntity performAction() {
        String distinctId = "user-" + System.currentTimeMillis();

        postHog.capture(
            distinctId,
            "action_performed",
            PostHogCaptureOptions
                .builder()
                .property("source", "api")
                .build()
        );

        return ResponseEntity.ok().build();
    }
}
```

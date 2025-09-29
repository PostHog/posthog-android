# PostHog Java Sample

This is a simple Java 1.8 example demonstrating how to use the PostHog Server library.

## Overview

This sample shows:
- Basic PostHog configuration
- Event capture
- Event capture with properties
- User identification
- User properties
- Feature flags
- Group analytics

## Running the Example

1. Update the API key in `PostHogJavaExample.java`:
   ```java
   PostHogConfig config = new PostHogConfig("your_actual_api_key_here")
   ```

2. Run the example:
   ```bash
   ./gradlew :posthog-samples:posthog-java-sample:run
   ```

## Key Features Demonstrated

### Event Tracking
```java
// Simple event
postHog.capture("button_clicked");

// Event with properties
Map<String, Object> properties = new HashMap<>();
properties.put("button_name", "signup");
postHog.capture("button_clicked", properties);
```

### User Management
```java
// Identify user
postHog.identify("user123");

// Set user properties
Map<String, Object> userProperties = new HashMap<>();
userProperties.put("email", "user@example.com");
postHog.capture("$set", userProperties);
```

### Feature Flags
```java
boolean isEnabled = postHog.isFeatureEnabled("new_feature", false);
Object flagValue = postHog.getFeatureFlag("new_feature");
```

### Groups
```java
Map<String, Object> groupProperties = new HashMap<>();
groupProperties.put("name", "Acme Corp");
postHog.group("company", "company_123", groupProperties);
```

## Requirements

- Java 1.8 or higher
- PostHog API key
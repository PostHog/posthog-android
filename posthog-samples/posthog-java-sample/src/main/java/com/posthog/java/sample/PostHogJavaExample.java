package com.posthog.java.sample;

import com.posthog.server.PostHog;
import com.posthog.server.PostHogCaptureOptions;
import com.posthog.server.PostHogConfig;
import com.posthog.server.PostHogFeatureFlagOptions;
import com.posthog.server.PostHogInterface;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * Interactive Java example demonstrating PostHog Server SDK usage.
 *
 * <p>This demo shows:
 * <ul>
 *   <li>Event capture</li>
 *   <li>User identification</li>
 *   <li>Feature flags (remote and local evaluation)</li>
 *   <li>Local evaluation with ETag polling</li>
 *   <li>Error tracking</li>
 * </ul>
 */
public class PostHogJavaExample {
    private static final BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

    public static void main(String[] args) {
        Properties props = loadProperties();
        String apiKey = props.getProperty("posthog.api.key");
        String host = props.getProperty("posthog.host", "https://us.i.posthog.com");

        // Personal API key is private and sensitive, so we recommend loading it from
        // environment variables or a secure vault
        String personalApiKey = System.getenv("POSTHOG_PERSONAL_API_KEY");

        // Validate configuration
        if (apiKey == null || apiKey.isEmpty() || apiKey.startsWith("phc_your")) {
            System.out.println("ERROR: Missing or invalid PostHog API key!");
            System.out.println("Please update posthog.api.key in application.properties");
            System.exit(1);
        }

        System.out.println("PostHog Java SDK Demo");
        System.out.println("=====================");
        System.out.println("Project API Key: configured");
        System.out.println("Personal API Key: " + (personalApiKey != null ? "configured" : "NOT SET (local evaluation disabled)"));
        System.out.println("Host: " + host);
        System.out.println();

        PostHogConfig config = PostHogConfig
                .builder(apiKey)
                .personalApiKey(personalApiKey)
                .host(host)
                .localEvaluation(personalApiKey != null)
                .debug(true)
                .flushAt(1) // Flush immediately for demo purposes
                .build();

        PostHogInterface posthog = PostHog.with(config);

        try {
            runMenu(posthog, personalApiKey != null);
        } finally {
            posthog.flush();
            posthog.close();
        }
    }

    private static void runMenu(PostHogInterface posthog, boolean hasPersonalApiKey) {
        while (true) {
            showMenu(hasPersonalApiKey);
            String choice = prompt("\nEnter your choice (1-6): ");

            switch (choice) {
                case "1":
                    runCaptureExamples(posthog);
                    break;
                case "2":
                    runIdentifyExamples(posthog);
                    break;
                case "3":
                    runFeatureFlagExamples(posthog);
                    break;
                case "4":
                    runLocalEvaluationExample(posthog, hasPersonalApiKey);
                    break;
                case "5":
                    runAllExamples(posthog, hasPersonalApiKey);
                    break;
                case "6":
                    System.out.println("Goodbye!");
                    return;
                default:
                    System.out.println("Invalid choice. Please select 1-6.");
                    continue;
            }

            System.out.println("\n" + "=".repeat(60));
            System.out.println("Example completed!");
            System.out.println("=".repeat(60));

            String again = prompt("\nWould you like to run another example? (y/N): ");
            if (!again.equalsIgnoreCase("y") && !again.equalsIgnoreCase("yes")) {
                System.out.println("Goodbye!");
                return;
            }
            System.out.println();
        }
    }

    private static void showMenu(boolean hasPersonalApiKey) {
        System.out.println("PostHog Java SDK Demo - Choose an example to run:");
        System.out.println();
        System.out.println("1. Capture events");
        System.out.println("2. Identify users");
        System.out.println("3. Feature flags (remote evaluation)");
        System.out.println("4. Local evaluation with ETag polling" +
                (hasPersonalApiKey ? "" : " (requires Personal API Key)"));
        System.out.println("5. Run all examples");
        System.out.println("6. Exit");
    }

    private static String prompt(String message) {
        System.out.print(message);
        try {
            String line = reader.readLine();
            return line != null ? line.trim() : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static void runCaptureExamples(PostHogInterface posthog) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("CAPTURE EVENTS");
        System.out.println("=".repeat(60));

        String distinctId = "user_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        System.out.println("\nUsing distinct ID: " + distinctId);

        // Simple capture
        System.out.println("\nCapturing 'page_view' event...");
        posthog.capture(distinctId, "page_view",
                PostHogCaptureOptions.builder()
                        .property("page", "/home")
                        .property("referrer", "https://google.com")
                        .build());
        System.out.println("   Event queued");

        // Capture with more properties
        System.out.println("\nCapturing 'button_clicked' event with properties...");
        posthog.capture(distinctId, "button_clicked",
                PostHogCaptureOptions.builder()
                        .property("button_id", "signup_cta")
                        .property("button_text", "Get Started")
                        .property("page", "/pricing")
                        .build());
        System.out.println("   Event queued");

        // Flush to ensure events are sent
        System.out.println("\nFlushing events...");
        posthog.flush();
        System.out.println("   Events sent to PostHog");
    }

    private static void runIdentifyExamples(PostHogInterface posthog) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("IDENTIFY USERS");
        System.out.println("=".repeat(60));

        String distinctId = "user_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        System.out.println("\nUsing distinct ID: " + distinctId);

        // Identify with properties
        System.out.println("\nIdentifying user with properties...");
        Map<String, Object> userProperties = new HashMap<>();
        userProperties.put("email", "test@example.com");
        userProperties.put("name", "Test User");
        userProperties.put("plan", "pro");
        posthog.identify(distinctId, userProperties);
        System.out.println("   User identified");

        // Group association
        System.out.println("\nAssociating user with a group...");
        Map<String, Object> groupProperties = new HashMap<>();
        groupProperties.put("name", "Acme Corporation");
        groupProperties.put("industry", "Technology");
        posthog.group(distinctId, "company", "acme_corp", groupProperties);
        System.out.println("   Group associated");

        // Alias
        System.out.println("\nCreating alias...");
        posthog.alias(distinctId, "user_alias_" + distinctId.substring(5));
        System.out.println("   Alias created");

        posthog.flush();
        System.out.println("   Events sent to PostHog");
    }

    private static void runFeatureFlagExamples(PostHogInterface posthog) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("FEATURE FLAGS (Remote Evaluation)");
        System.out.println("=".repeat(60));

        String distinctId = "user_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        System.out.println("\nUsing distinct ID: " + distinctId);

        // Check a simple boolean flag
        System.out.println("\nChecking feature flag 'beta-feature'...");
        Boolean isEnabled = posthog.isFeatureEnabled(distinctId, "beta-feature", false);
        System.out.println("   Result: " + isEnabled);

        // Get flag with variant
        System.out.println("\nGetting feature flag 'multi-variate-flag'...");
        Object flagValue = posthog.getFeatureFlag(distinctId, "multi-variate-flag", "default");
        System.out.println("   Value: " + flagValue);

        // Get flag payload
        System.out.println("\nGetting feature flag payload 'multi-variate-flag'...");
        Object payload = posthog.getFeatureFlagPayload(distinctId, "multi-variate-flag");
        System.out.println("   Payload: " + payload);

        // Feature flag with person properties
        System.out.println("\nChecking flag with person properties...");
        Boolean hasFeature = posthog.isFeatureEnabled(
                distinctId,
                "file-previews",
                PostHogFeatureFlagOptions.builder()
                        .defaultValue(false)
                        .personProperty("email", "example@example.com")
                        .personProperty("plan", "enterprise")
                        .build());
        System.out.println("   Result: " + hasFeature);
    }

    private static void runLocalEvaluationExample(PostHogInterface posthog, boolean hasPersonalApiKey) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("LOCAL EVALUATION WITH ETAG POLLING");
        System.out.println("=".repeat(60));

        if (!hasPersonalApiKey) {
            System.out.println("\nThis example requires a Personal API Key to be set.");
            System.out.println("Set POSTHOG_PERSONAL_API_KEY environment variable.");
            System.out.println("Personal API keys can be created at:");
            System.out.println("https://us.posthog.com/settings/user-api-keys");
            return;
        }

        System.out.println("\nThe SDK polls for feature flag definitions and uses ETags to");
        System.out.println("minimize bandwidth. When flags haven't changed, the server");
        System.out.println("returns HTTP 304 Not Modified instead of the full payload.");
        System.out.println();
        System.out.println("Watch the debug logs for:");
        System.out.println("  - 'Loading feature flags for local evaluation'");
        System.out.println("  - 'Loaded N feature flags' (first request, full response)");
        System.out.println("  - 'Feature flags not modified (304)' (subsequent requests)");
        System.out.println();
        System.out.println("Triggering flag definition reload every 5 seconds for 30 seconds...");
        System.out.println();

        int pollIntervalMs = 5000;
        int totalDurationMs = 30000;
        int iterations = totalDurationMs / pollIntervalMs;

        try {
            for (int i = 0; i < iterations; i++) {
                String timestamp = java.time.LocalTime.now().format(
                        java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));

                System.out.println("[" + timestamp + "] Reload #" + (i + 1) + "...");

                // Trigger a reload of flag definitions - this is what uses ETag
                posthog.reloadFeatureFlags();

                if (i < iterations - 1) {
                    Thread.sleep(pollIntervalMs);
                }
            }
        } catch (InterruptedException e) {
            System.out.println("\nPolling interrupted");
            Thread.currentThread().interrupt();
        }

        System.out.println("\nDone! Check the logs above for ETag behavior.");
    }

    private static void runErrorTrackingExample(PostHogInterface posthog) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("ERROR TRACKING");
        System.out.println("=".repeat(60));

        String distinctId = "user_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        System.out.println("\nUsing distinct ID: " + distinctId);

        System.out.println("\nCapturing an exception...");
        try {
            throw new RuntimeException("Test exception for PostHog demo");
        } catch (Exception e) {
            Map<String, Object> exceptionProperties = new HashMap<>();
            exceptionProperties.put("service", "demo-app");
            exceptionProperties.put("context", "error_tracking_example");
            posthog.captureException(e, distinctId, exceptionProperties);
            System.out.println("   Exception captured: " + e.getMessage());
        }

        posthog.flush();
        System.out.println("   Events sent to PostHog");
    }

    private static void runAllExamples(PostHogInterface posthog, boolean hasPersonalApiKey) {
        System.out.println("\nRunning all examples...");

        runCaptureExamples(posthog);
        runIdentifyExamples(posthog);
        runFeatureFlagExamples(posthog);
        runErrorTrackingExample(posthog);
        runLocalEvaluationExample(posthog, hasPersonalApiKey);
    }

    private static Properties loadProperties() {
        Properties properties = new Properties();
        try (InputStream input = PostHogJavaExample.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (input != null) {
                properties.load(input);
            }
        } catch (IOException e) {
            // If properties file not found, return empty properties
            // Allows fallback to hardcoded defaults
        }
        return properties;
    }
}

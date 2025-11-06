package com.posthog.java.sample;

import com.posthog.server.PostHog;
import com.posthog.server.PostHogCaptureOptions;
import com.posthog.server.PostHogConfig;
import com.posthog.server.PostHogFeatureFlagOptions;
import com.posthog.server.PostHogInterface;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Simple Java 1.8 example demonstrating PostHog usage
 */
public class PostHogJavaExample {
    public static void main(String[] args) {
        Properties props = loadProperties();
        String apiKey = props.getProperty("posthog.api.key");
        String host = props.getProperty("posthog.host");

        // Personal API key is private and sensitive, so we recommend loading it from
        // environment variables or a secure vault
        String personalApiKey = System.getenv("POSTHOG_PERSONAL_API_KEY");

        PostHogConfig config = PostHogConfig
                .builder(apiKey)
                .personalApiKey(personalApiKey)
                .host(host)
                .localEvaluation(true)
                .debug(true)
                .build();

        PostHogInterface posthog = PostHog.with(config);

        posthog.group("distinct-id", "company", "some-company-id");
        posthog.capture(
                "distinct-id",
                "new-purchase",
                PostHogCaptureOptions
                        .builder()
                        .property("item", "SKU-0000")
                        .property("sale", false)
                        .build());

        HashMap<String, Object> userProperties = new HashMap<>();
        userProperties.put("email", "user@example.com");
        posthog.identify("distinct-id", userProperties);

        // AVOID - Anonymous inner class holds reference to outer class.
        // The following won't serialize properly.
        // posthog.identify("user-123", new HashMap<String, Object>() {{
        // put("key", "value");
        // }});

        posthog.alias("distinct-id", "alias-id");

        // Feature flag examples with local evaluation
        if (posthog.isFeatureEnabled("distinct-id", "beta-feature", false)) {
            System.out.println("The feature is enabled.");
        }

        Object flagValue = posthog.getFeatureFlag("distinct-id", "multi-variate-flag", "default");
        String flagVariate = flagValue instanceof String ? (String) flagValue : "default";
        Object flagPayload = posthog.getFeatureFlagPayload("distinct-id", "multi-variate-flag");

        System.out.println("The flag variant was: " + flagVariate);
        System.out.println("Received flag payload: " + flagPayload);

        Boolean hasFilePreview = posthog.isFeatureEnabled(
                "distinct-id",
                "file-previews",
                PostHogFeatureFlagOptions
                        .builder()
                        .defaultValue(false)
                        .personProperty("email", "example@example.com")
                        .build());

        System.out.println("File previews enabled: " + hasFilePreview);

        try {
            throw new RuntimeException("Test exception");
        } catch (Exception e) {
            Map<String, Object> exceptionProperties = new HashMap<>();
            exceptionProperties.put("service", "weather-api");
            posthog.captureException(e, exceptionProperties);
        }

        posthog.flush();
        posthog.close();
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
